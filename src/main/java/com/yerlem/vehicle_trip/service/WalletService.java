package com.yerlem.vehicle_trip.service;

import com.yerlem.vehicle_trip.dto.WalletResponse;
import com.yerlem.vehicle_trip.dto.WalletTransactionResponse;
import com.yerlem.vehicle_trip.entity.Trip;
import com.yerlem.vehicle_trip.entity.User;
import com.yerlem.vehicle_trip.entity.Wallet;
import com.yerlem.vehicle_trip.entity.WalletTransaction;
import com.yerlem.vehicle_trip.enums.UserStatus;
import com.yerlem.vehicle_trip.enums.WalletTxType;
import com.yerlem.vehicle_trip.exception.ApiException;
import com.yerlem.vehicle_trip.repository.UserRepository;
import com.yerlem.vehicle_trip.repository.WalletRepository;
import com.yerlem.vehicle_trip.repository.WalletTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class WalletService {

    private final WalletRepository walletRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    /** Kullanıcının cüzdan bakiyesini getirir. */
    @Transactional(readOnly = true)
    public WalletResponse getWallet(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Kullanıcı bulunamadı.");
        }
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", "Cüzdan bulunamadı."));
        return WalletResponse.from(wallet);
    }

    /**
     * Cüzdana mock bakiye yükler (TOP_UP hareketi oluşturur).
     */
    @Transactional
    public WalletResponse topUp(Long userId, BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Yükleme tutarı 0'dan büyük olmalıdır.");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Kullanıcı bulunamadı."));

        if (user.getStatus() == UserStatus.SUSPENDED) {
            throw new ApiException(HttpStatus.FORBIDDEN, "USER_SUSPENDED", "Hesap askıya alınmış.");
        }

        BigDecimal normalizedAmount = amount.setScale(2, RoundingMode.HALF_UP);

        Wallet wallet = walletRepository.findByUserIdForUpdate(userId)
                .orElseGet(() -> {
                    Wallet newWallet = new Wallet();
                    newWallet.setUser(user);
                    newWallet.setBalance(BigDecimal.ZERO);
                    return walletRepository.save(newWallet);
                });

        BigDecimal newBalance = wallet.getBalance().add(normalizedAmount);
        wallet.setBalance(newBalance);
        walletRepository.save(wallet);

        WalletTransaction tx = new WalletTransaction();
        tx.setWallet(wallet);
        tx.setType(WalletTxType.TOP_UP);
        tx.setAmount(normalizedAmount);
        tx.setBalanceAfter(newBalance);
        tx.setDescription("Cüzdan bakiye yükleme (Mock)");
        tx.setCreatedAt(Instant.now(clock));
        walletTransactionRepository.save(tx);

        log.info("Cüzdana bakiye yüklendi. userId={}, tutar={}, yeniBakiye={}", userId, normalizedAmount, newBalance);
        return WalletResponse.from(wallet);
    }

    /** Cüzdan hesap hareketlerini yeniden eskiye sıralı getirir. */
    @Transactional(readOnly = true)
    public List<WalletTransactionResponse> getTransactions(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Kullanıcı bulunamadı.");
        }
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", "Cüzdan bulunamadı."));

        return walletTransactionRepository.findByWalletIdOrderByCreatedAtDesc(wallet.getId())
                .stream()
                .map(WalletTransactionResponse::from)
                .toList();
    }

    /**
     * Seyahat ücretini cüzdandan tahsil eder (D8, D15, D18).
     * - Tutar 0 ise wallet_transactions'a yazılmaz (amount > 0 CHECK kısıtı), başarılı döner (D15).
     * - Cüzdan satırı kilitlenir (PESSIMISTIC_WRITE); mükerrer tahsilat kontrolü kilitten SONRA yapılır (D18).
     * - Aynı seyahat için daha önce TRIP_CHARGE hareketi varsa tekrar işlem yapmaz (idempotent, D7).
     * - Bakiye yetersizse 402 PAYMENT_FAILED fırlatır; işlem geri alınır (D8).
     */
    @Transactional
    public void charge(Trip trip, BigDecimal amount) {
        charge(trip, amount, null);
    }

    /**
     * Adım 17 / B6: hareket açıklaması verilebilir varyant (mesafe + hasar dökümü için).
     * description null ise mevcut varsayılan açıklama kullanılır; diğer davranış birebir aynı.
     */
    @Transactional
    public void charge(Trip trip, BigDecimal amount, String description) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Tahsilat tutarı negatif olamaz.");
        }

        BigDecimal normalizedAmount = amount.setScale(2, RoundingMode.HALF_UP);

        // D15: Sıfır tutarlı seyahat tahsilatı — kilit almaya gerek yok
        if (normalizedAmount.compareTo(BigDecimal.ZERO) == 0) {
            log.info("Seyahat ücreti 0 TL olduğu için cüzdan hareketi oluşturulmadı. tripId={}", trip.getId());
            return;
        }

        Long userId = trip.getUser().getId();
        Wallet wallet = walletRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", "Cüzdan bulunamadı."));

        // D7: Idempotent kontrolü — kilit ALTINDA yapılır; eşzamanlı ikinci tahsilat kilidi bekler,
        // sonra mevcut hareketi görüp temiz döner (DB kısmi unique index son savunmadır).
        if (trip.getId() != null && walletTransactionRepository.existsByTripIdAndType(trip.getId(), WalletTxType.TRIP_CHARGE)) {
            log.warn("Seyahat için zaten tahsilat hareketi mevcut, mükerrer tahsilat atlandı. tripId={}", trip.getId());
            return;
        }

        if (wallet.getBalance().compareTo(normalizedAmount) < 0) {
            throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "PAYMENT_FAILED",
                    "Yetersiz bakiye: seyahat ücreti (" + normalizedAmount + " TL) tahsil edilemedi. Mevcut bakiye: "
                            + wallet.getBalance() + " TL.");
        }

        BigDecimal newBalance = wallet.getBalance().subtract(normalizedAmount);
        wallet.setBalance(newBalance);
        walletRepository.save(wallet);

        WalletTransaction tx = new WalletTransaction();
        tx.setWallet(wallet);
        tx.setTrip(trip);
        tx.setType(WalletTxType.TRIP_CHARGE);
        tx.setAmount(normalizedAmount);
        tx.setBalanceAfter(newBalance);
        tx.setDescription(description != null ? description
                : "Seyahat ücreti tahsilatı (Seyahat #" + trip.getId() + ")");
        tx.setCreatedAt(Instant.now(clock));
        walletTransactionRepository.save(tx);

        log.info("Seyahat ücreti tahsil edildi. tripId={}, userId={}, tutar={}, kalanBakiye={}",
                trip.getId(), userId, normalizedAmount, newBalance);
    }

    /** Kullanıcının belirtilen tutarı karşılayacak yeterli bakiyesi olup olmadığını sorgular. */
    @Transactional(readOnly = true)
    public boolean hasSufficientBalance(Long userId, BigDecimal requiredAmount) {
        if (requiredAmount == null || requiredAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return true;
        }
        return walletRepository.findByUserId(userId)
                .map(w -> w.getBalance().compareTo(requiredAmount) >= 0)
                .orElse(false);
    }
}

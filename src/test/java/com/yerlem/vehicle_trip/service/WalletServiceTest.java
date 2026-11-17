package com.yerlem.vehicle_trip.service;

import com.yerlem.vehicle_trip.dto.WalletResponse;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    @Mock
    private WalletRepository walletRepository;
    @Mock
    private WalletTransactionRepository walletTransactionRepository;
    @Mock
    private UserRepository userRepository;

    private WalletService walletService;
    private User user;
    private Wallet wallet;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        walletService = new WalletService(walletRepository, walletTransactionRepository, userRepository, clock);

        user = new User();
        user.setId(1L);
        user.setPhone("+905550000001");
        user.setStatus(UserStatus.VERIFIED);

        wallet = new Wallet();
        wallet.setId(10L);
        wallet.setUser(user);
        wallet.setBalance(new BigDecimal("100.00"));
    }

    @Test
    @DisplayName("topUp: geçerli tutar cüzdana eklenir ve TOP_UP hareketi oluşturulur")
    void topUp_success() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(walletRepository.findByUserIdForUpdate(1L)).thenReturn(Optional.of(wallet));

        WalletResponse response = walletService.topUp(1L, new BigDecimal("50.50"));

        assertThat(response.balance()).isEqualByComparingTo("150.50");
        assertThat(wallet.getBalance()).isEqualByComparingTo("150.50");

        ArgumentCaptor<WalletTransaction> txCaptor = ArgumentCaptor.forClass(WalletTransaction.class);
        verify(walletTransactionRepository).save(txCaptor.capture());
        WalletTransaction tx = txCaptor.getValue();
        assertThat(tx.getType()).isEqualTo(WalletTxType.TOP_UP);
        assertThat(tx.getAmount()).isEqualByComparingTo("50.50");
        assertThat(tx.getBalanceAfter()).isEqualByComparingTo("150.50");
        assertThat(tx.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("topUp: kullanıcı askıya alınmışsa 403 USER_SUSPENDED fırlatılır")
    void topUp_userSuspended() {
        user.setStatus(UserStatus.SUSPENDED);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> walletService.topUp(1L, new BigDecimal("50.00")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(apiEx.getCode()).isEqualTo("USER_SUSPENDED");
                });

        verify(walletRepository, never()).save(any());
        verify(walletTransactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("topUp: sıfır veya negatif tutarda 400 VALIDATION_ERROR fırlatılır")
    void topUp_invalidAmount() {
        assertThatThrownBy(() -> walletService.topUp(1L, BigDecimal.ZERO))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(apiEx.getCode()).isEqualTo("VALIDATION_ERROR");
                });
    }

    @Test
    @DisplayName("charge: bakiye yeterliyse tahsilat yapılır ve TRIP_CHARGE kaydedilir")
    void charge_success() {
        Trip trip = new Trip();
        trip.setId(99L);
        trip.setUser(user);

        when(walletTransactionRepository.existsByTripIdAndType(99L, WalletTxType.TRIP_CHARGE)).thenReturn(false);
        when(walletRepository.findByUserIdForUpdate(1L)).thenReturn(Optional.of(wallet));

        walletService.charge(trip, new BigDecimal("45.00"));

        assertThat(wallet.getBalance()).isEqualByComparingTo("55.00");

        ArgumentCaptor<WalletTransaction> txCaptor = ArgumentCaptor.forClass(WalletTransaction.class);
        verify(walletTransactionRepository).save(txCaptor.capture());
        WalletTransaction tx = txCaptor.getValue();
        assertThat(tx.getType()).isEqualTo(WalletTxType.TRIP_CHARGE);
        assertThat(tx.getTrip()).isEqualTo(trip);
        assertThat(tx.getAmount()).isEqualByComparingTo("45.00");
        assertThat(tx.getBalanceAfter()).isEqualByComparingTo("55.00");
    }

    @Test
    @DisplayName("charge: bakiye yetersizse 402 PAYMENT_FAILED fırlatılır, bakiye değişmez")
    void charge_insufficientBalance() {
        Trip trip = new Trip();
        trip.setId(99L);
        trip.setUser(user);

        when(walletTransactionRepository.existsByTripIdAndType(99L, WalletTxType.TRIP_CHARGE)).thenReturn(false);
        when(walletRepository.findByUserIdForUpdate(1L)).thenReturn(Optional.of(wallet));

        assertThatThrownBy(() -> walletService.charge(trip, new BigDecimal("150.00")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
                    assertThat(apiEx.getCode()).isEqualTo("PAYMENT_FAILED");
                });

        assertThat(wallet.getBalance()).isEqualByComparingTo("100.00");
        verify(walletTransactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("charge (D15): tutar 0 ise bakiye düşmez ve hareket kaydedilmez")
    void charge_zeroAmount() {
        Trip trip = new Trip();
        trip.setId(99L);
        trip.setUser(user);

        walletService.charge(trip, BigDecimal.ZERO);

        verify(walletRepository, never()).findByUserIdForUpdate(any());
        verify(walletTransactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("charge (D7/D18): cüzdan kilidi alındıktan sonra zaten TRIP_CHARGE varsa mükerrer tahsilat yapılmaz")
    void charge_idempotent() {
        Trip trip = new Trip();
        trip.setId(99L);
        trip.setUser(user);

        when(walletRepository.findByUserIdForUpdate(1L)).thenReturn(Optional.of(wallet));
        when(walletTransactionRepository.existsByTripIdAndType(99L, WalletTxType.TRIP_CHARGE)).thenReturn(true);

        walletService.charge(trip, new BigDecimal("45.00"));

        verify(walletRepository).findByUserIdForUpdate(1L);
        verify(walletRepository, never()).save(any());
        verify(walletTransactionRepository, never()).save(any());
        assertThat(wallet.getBalance()).isEqualByComparingTo("100.00");
    }
}

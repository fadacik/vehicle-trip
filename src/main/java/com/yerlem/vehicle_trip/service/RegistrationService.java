package com.yerlem.vehicle_trip.service;

import com.yerlem.vehicle_trip.config.OtpProperties;
import com.yerlem.vehicle_trip.dto.RegisterRequest;
import com.yerlem.vehicle_trip.dto.RegisterResponse;
import com.yerlem.vehicle_trip.dto.ResendOtpRequest;
import com.yerlem.vehicle_trip.dto.UserResponse;
import com.yerlem.vehicle_trip.dto.VerifyOtpRequest;
import com.yerlem.vehicle_trip.entity.OtpCode;
import com.yerlem.vehicle_trip.entity.User;
import com.yerlem.vehicle_trip.entity.Wallet;
import com.yerlem.vehicle_trip.enums.UserStatus;
import com.yerlem.vehicle_trip.exception.ApiException;
import com.yerlem.vehicle_trip.repository.UserRepository;
import com.yerlem.vehicle_trip.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class RegistrationService {

    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final OtpService otpService;
    private final OtpProperties otpProperties;
    private final Clock clock;

    /**
     * Yeni kayit PENDING olusturulur ve OTP gonderilir.
     * - Telefon VERIFIED/SUSPENDED bir kullaniciya aitse: 409 (ikinci aktif kayit yok).
     * - Telefon PENDING bir kayda aitse: ayni kayit guncellenir ve yeni OTP uretilir (mukerrer kayit olusmaz).
     */
    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        String phone = request.phone().trim();

        User user = userRepository.findByPhoneForUpdate(phone).orElse(null);
        if (user != null && user.getStatus() != UserStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "PHONE_ALREADY_REGISTERED",
                    "Bu telefon numarasi ile zaten bir kayit var.");
        }
        if (user == null) {
            user = new User();
            user.setPhone(phone);
            user.setStatus(UserStatus.PENDING);
            user.setCreatedAt(Instant.now(clock));
        }
        user.setFullName(request.fullName().trim());
        user.setNationalId(request.nationalId());
        user.setProfilePhotoUrl(request.profilePhotoUrl());
        user = userRepository.save(user);

        return toRegisterResponse(user, otpService.issue(user));
    }

    /** Dogru OTP -> kullanici VERIFIED olur, OTP tuketilir, bos bir cuzdan acilir. */
    @Transactional
    public UserResponse verifyOtp(VerifyOtpRequest request) {
        User user = findUserForUpdate(request.phone());
        if (user.getStatus() == UserStatus.VERIFIED) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_VERIFIED", "Kullanici zaten dogrulanmis.");
        }
        if (user.getStatus() == UserStatus.SUSPENDED) {
            throw new ApiException(HttpStatus.FORBIDDEN, "USER_SUSPENDED", "Hesap askiya alinmis.");
        }

        otpService.verifyAndConsume(user, request.code());

        user.setStatus(UserStatus.VERIFIED);
        user.setVerifiedAt(Instant.now(clock));

        if (walletRepository.findByUserId(user.getId()).isEmpty()) {
            Wallet wallet = new Wallet();
            wallet.setUser(user);
            wallet.setBalance(BigDecimal.ZERO);
            walletRepository.save(wallet);
        }
        return UserResponse.from(user);
    }

    @Transactional
    public RegisterResponse resendOtp(ResendOtpRequest request) {
        User user = findUserForUpdate(request.phone());
        if (user.getStatus() != UserStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_PENDING",
                    "Yalnizca dogrulama bekleyen kayitlar icin OTP istenebilir.");
        }
        return toRegisterResponse(user, otpService.issue(user));
    }

    private User findUserForUpdate(String phone) {
        return userRepository.findByPhoneForUpdate(phone.trim())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Kullanici bulunamadi."));
    }

    private RegisterResponse toRegisterResponse(User user, OtpCode otp) {
        return new RegisterResponse(
                user.getId(),
                user.getPhone(),
                user.getStatus(),
                otp.getExpiresAt(),
                otpProperties.exposeInResponse() ? otp.getCode() : null,
                "OTP gonderildi. " + otpProperties.validityMinutes() + " dakika icinde dogrulayin.");
    }
}

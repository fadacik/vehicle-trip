package com.yerlem.vehicle_trip.service;

import com.yerlem.vehicle_trip.config.OtpProperties;
import com.yerlem.vehicle_trip.entity.OtpCode;
import com.yerlem.vehicle_trip.entity.User;
import com.yerlem.vehicle_trip.exception.ApiException;
import com.yerlem.vehicle_trip.integration.SmsSender;
import com.yerlem.vehicle_trip.repository.OtpCodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * OTP uretimi ve dogrulamasi. Cagiran servisin transaction'i icinde calisir.
 * Kurallar: 6 hane, 5 dk gecerli, tek kullanimlik, yeni kod eskisini gecersiz kilar.
 */
@Service
@RequiredArgsConstructor
public class OtpService {

    private final OtpCodeRepository otpCodeRepository;
    private final SmsSender smsSender;
    private final OtpProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public OtpCode issue(User user) {
        Instant now = Instant.now(clock);
        otpCodeRepository.invalidateUnused(user.getId(), now);

        OtpCode otp = new OtpCode();
        otp.setUser(user);
        otp.setCode(generateCode());
        otp.setCreatedAt(now);
        otp.setExpiresAt(now.plus(properties.validityMinutes(), ChronoUnit.MINUTES));
        otpCodeRepository.save(otp);

        smsSender.send(user.getPhone(),
                "Dogrulama kodunuz: " + otp.getCode() + " (" + properties.validityMinutes() + " dakika gecerli)");
        return otp;
    }

    public void verifyAndConsume(User user, String code) {
        Instant now = Instant.now(clock);

        OtpCode otp = otpCodeRepository.findFirstByUserIdAndUsedFalseOrderByCreatedAtDesc(user.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "OTP_INVALID",
                        "Gecerli bir dogrulama kodu bulunamadi. Yeni kod isteyin."));

        if (!now.isBefore(otp.getExpiresAt())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "OTP_EXPIRED",
                    "Dogrulama kodunun suresi doldu. Yeni kod isteyin.");
        }
        boolean matches = MessageDigest.isEqual(
                otp.getCode().getBytes(StandardCharsets.UTF_8), code.getBytes(StandardCharsets.UTF_8));
        if (!matches) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "OTP_INVALID", "Dogrulama kodu hatali.");
        }

        otp.setUsed(true);
        otp.setUsedAt(now);
    }

    private String generateCode() {
        int bound = (int) Math.pow(10, properties.length());
        return String.format("%0" + properties.length() + "d", random.nextInt(bound));
    }
}

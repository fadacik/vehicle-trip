package com.yerlem.vehicle_trip.service;

import com.yerlem.vehicle_trip.config.OtpProperties;
import com.yerlem.vehicle_trip.entity.OtpCode;
import com.yerlem.vehicle_trip.entity.User;
import com.yerlem.vehicle_trip.exception.ApiException;
import com.yerlem.vehicle_trip.integration.SmsSender;
import com.yerlem.vehicle_trip.repository.OtpCodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OtpServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    @Mock
    private OtpCodeRepository otpCodeRepository;
    @Mock
    private SmsSender smsSender;

    private OtpService otpService;
    private User user;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        otpService = new OtpService(otpCodeRepository, smsSender, new OtpProperties(6, 5, false), clock);
        user = new User();
        user.setId(1L);
        user.setPhone("+905550000009");
    }

    private OtpCode otp(String code, Instant expiresAt) {
        OtpCode otp = new OtpCode();
        otp.setUser(user);
        otp.setCode(code);
        otp.setExpiresAt(expiresAt);
        return otp;
    }

    @Test
    void issue_createsSixDigitCodeValidForFiveMinutes_andSendsSms() {
        otpService.issue(user);

        ArgumentCaptor<OtpCode> captor = ArgumentCaptor.forClass(OtpCode.class);
        verify(otpCodeRepository).invalidateUnused(1L, NOW);
        verify(otpCodeRepository).save(captor.capture());
        OtpCode saved = captor.getValue();

        assertThat(saved.getCode()).matches("[0-9]{6}");
        assertThat(saved.getExpiresAt()).isEqualTo(NOW.plusSeconds(300));
        assertThat(saved.isUsed()).isFalse();
        verify(smsSender).send(eq("+905550000009"), anyString());
    }

    @Test
    void verify_correctCode_marksOtpAsUsed() {
        OtpCode otp = otp("123456", NOW.plusSeconds(120));
        when(otpCodeRepository.findFirstByUserIdAndUsedFalseOrderByCreatedAtDesc(1L)).thenReturn(Optional.of(otp));

        otpService.verifyAndConsume(user, "123456");

        assertThat(otp.isUsed()).isTrue();
        assertThat(otp.getUsedAt()).isEqualTo(NOW);
    }

    @Test
    void verify_wrongCode_isRejected_andOtpStaysUnused() {
        OtpCode otp = otp("123456", NOW.plusSeconds(120));
        when(otpCodeRepository.findFirstByUserIdAndUsedFalseOrderByCreatedAtDesc(1L)).thenReturn(Optional.of(otp));

        assertThatThrownBy(() -> otpService.verifyAndConsume(user, "000000"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo("OTP_INVALID");
        assertThat(otp.isUsed()).isFalse();
    }

    @Test
    void verify_expiredCode_isRejected() {
        OtpCode otp = otp("123456", NOW.minusSeconds(1));
        when(otpCodeRepository.findFirstByUserIdAndUsedFalseOrderByCreatedAtDesc(1L)).thenReturn(Optional.of(otp));

        assertThatThrownBy(() -> otpService.verifyAndConsume(user, "123456"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo("OTP_EXPIRED");
        assertThat(otp.isUsed()).isFalse();
    }

    @Test
    void verify_alreadyUsedOrMissingOtp_isRejected() {
        when(otpCodeRepository.findFirstByUserIdAndUsedFalseOrderByCreatedAtDesc(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> otpService.verifyAndConsume(user, "123456"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo("OTP_INVALID");
    }
}

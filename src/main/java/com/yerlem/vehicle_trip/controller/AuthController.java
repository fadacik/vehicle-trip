package com.yerlem.vehicle_trip.controller;

import com.yerlem.vehicle_trip.dto.RegisterRequest;
import com.yerlem.vehicle_trip.dto.RegisterResponse;
import com.yerlem.vehicle_trip.dto.ResendOtpRequest;
import com.yerlem.vehicle_trip.dto.UserResponse;
import com.yerlem.vehicle_trip.dto.VerifyOtpRequest;
import com.yerlem.vehicle_trip.service.RegistrationService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth", description = "Kayit ve OTP dogrulama")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final RegistrationService registrationService;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse register(@Valid @RequestBody RegisterRequest request) {
        return registrationService.register(request);
    }

    @PostMapping("/verify-otp")
    public UserResponse verifyOtp(@Valid @RequestBody VerifyOtpRequest request) {
        return registrationService.verifyOtp(request);
    }

    @PostMapping("/resend-otp")
    public RegisterResponse resendOtp(@Valid @RequestBody ResendOtpRequest request) {
        return registrationService.resendOtp(request);
    }
}

package com.yerlem.vehicle_trip.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.yerlem.vehicle_trip.enums.UserStatus;

import java.time.Instant;

/** otp alani yalnizca app.otp.expose-in-response=true iken (gelistirme) doludur. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RegisterResponse(
        Long userId,
        String phone,
        UserStatus status,
        Instant otpExpiresAt,
        String otp,
        String message
) {
}

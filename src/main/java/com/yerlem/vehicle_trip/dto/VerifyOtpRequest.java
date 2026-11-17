package com.yerlem.vehicle_trip.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record VerifyOtpRequest(
        @NotBlank(message = "Telefon zorunludur")
        String phone,

        @NotBlank(message = "OTP zorunludur")
        @Pattern(regexp = "[0-9]{6}", message = "OTP 6 haneli olmali")
        String code
) {
}

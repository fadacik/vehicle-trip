package com.yerlem.vehicle_trip.dto;

import jakarta.validation.constraints.NotBlank;

public record ResendOtpRequest(
        @NotBlank(message = "Telefon zorunludur")
        String phone
) {
}

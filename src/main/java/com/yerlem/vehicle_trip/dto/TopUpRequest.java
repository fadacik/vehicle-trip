package com.yerlem.vehicle_trip.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** Cüzdan bakiye yükleme isteği (mock ödeme). */
public record TopUpRequest(
        @NotNull(message = "Yükleme tutarı zorunludur.")
        @DecimalMin(value = "0.01", message = "Yükleme tutarı en az 0.01 olmalıdır.")
        BigDecimal amount) {
}

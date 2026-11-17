package com.yerlem.vehicle_trip.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Seyahat tamamlama isteği (D14, D16): tamamlama anındaki son konum.
 * Enlem/boylam aralık ve hız >= 0 kontrolleri servis katmanında
 * (TripLocationWriter.appendLocation) yapılır ve 400 INVALID_LOCATION döner;
 * bu yüzden bean validation'da yalnızca null kontrolü vardır.
 * recordedAt verilmezse sunucu saati (Clock) kullanılır.
 */
public record CompleteTripRequest(
        @NotNull(message = "Enlem (latitude) zorunludur.")
        Double latitude,

        @NotNull(message = "Boylam (longitude) zorunludur.")
        Double longitude,

        BigDecimal speedKmh,

        Instant recordedAt) {
}

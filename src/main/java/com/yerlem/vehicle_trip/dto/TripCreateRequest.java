package com.yerlem.vehicle_trip.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/** Seyahat oluşturma isteği (D17: kimlik doğrulama yok, userId istekle gelir). */
public record TripCreateRequest(
        @NotNull(message = "Kullanıcı ID zorunludur.")
        Long userId,

        @NotNull(message = "Araç ID zorunludur.")
        Long vehicleId,

        @NotNull(message = "Hedef enlem (latitude) zorunludur.")
        @DecimalMin(value = "-90.0", message = "Hedef enlem -90 ile 90 arasında olmalıdır.")
        @DecimalMax(value = "90.0", message = "Hedef enlem -90 ile 90 arasında olmalıdır.")
        Double destinationLatitude,

        @NotNull(message = "Hedef boylam (longitude) zorunludur.")
        @DecimalMin(value = "-180.0", message = "Hedef boylam -180 ile 180 arasında olmalıdır.")
        @DecimalMax(value = "180.0", message = "Hedef boylam -180 ile 180 arasında olmalıdır.")
        Double destinationLongitude) {
}

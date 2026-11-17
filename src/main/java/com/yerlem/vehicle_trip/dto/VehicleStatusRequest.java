package com.yerlem.vehicle_trip.dto;

import com.yerlem.vehicle_trip.enums.VehicleStatus;
import jakarta.validation.constraints.NotNull;

/** Araç durumunu değiştirme isteği (manuel geçişler için). */
public record VehicleStatusRequest(
        @NotNull(message = "Durum alanı boş olamaz.")
        VehicleStatus status) {
}


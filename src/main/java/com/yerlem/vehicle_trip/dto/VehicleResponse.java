package com.yerlem.vehicle_trip.dto;

import com.yerlem.vehicle_trip.enums.VehicleStatus;
import java.math.BigDecimal;

/** Araç listesi ve detay yanıtı. */
public record VehicleResponse(
        Long id,
        String plate,
        String brand,
        String model,
        String city,
        Double latitude,
        Double longitude,
        BigDecimal totalRangeKm,
        BigDecimal remainingRangeKm,
        VehicleStatus status) {
}


package com.yerlem.vehicle_trip.dto;

import com.yerlem.vehicle_trip.enums.VehicleStatus;
import java.math.BigDecimal;
import java.util.List;

/** Tek araç detayı — kullanım alanlarını da içerir. */
public record VehicleDetailResponse(
        Long id,
        String plate,
        String brand,
        String model,
        String city,
        Double latitude,
        Double longitude,
        BigDecimal totalRangeKm,
        BigDecimal remainingRangeKm,
        VehicleStatus status,
        List<VehicleZoneResponse> zones) {
}


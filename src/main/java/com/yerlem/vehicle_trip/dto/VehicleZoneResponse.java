package com.yerlem.vehicle_trip.dto;

import java.math.BigDecimal;

/** Aracın kullanım alanı bilgisi. */
public record VehicleZoneResponse(
        Long id,
        String name,
        Double centerLatitude,
        Double centerLongitude,
        BigDecimal radiusKm) {
}


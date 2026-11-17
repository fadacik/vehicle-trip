package com.yerlem.vehicle_trip.dto;

import com.yerlem.vehicle_trip.entity.TripWarning;
import com.yerlem.vehicle_trip.enums.WarningType;

import java.time.Instant;

/** Seyahat uyarısı (SPEED_LIMIT / DURATION_EXCEEDED). */
public record TripWarningResponse(
        Long id,
        WarningType warningType,
        String message,
        Instant createdAt) {

    public static TripWarningResponse from(TripWarning warning) {
        return new TripWarningResponse(
                warning.getId(),
                warning.getWarningType(),
                warning.getMessage(),
                warning.getCreatedAt());
    }
}

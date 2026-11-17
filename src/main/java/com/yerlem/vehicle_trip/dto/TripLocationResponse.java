package com.yerlem.vehicle_trip.dto;

import com.yerlem.vehicle_trip.entity.TripLocation;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Kaydedilen konum bilgisi (D9, D10).
 * duplicate=true: aynı recordedAt ile gelen tekrar istek; yeni satır eklenmemiştir.
 * Not: trip bilgisine yanıt zaten /api/trips/{id}/... altında olduğundan dahil edilmez;
 * entity'deki lazy trip alanına transactionsız katmanda erişim hataya yol açardı.
 */
public record TripLocationResponse(
        Long id,
        Double latitude,
        Double longitude,
        Instant recordedAt,
        String city,
        BigDecimal speedKmh,
        boolean duplicate) {

    public static TripLocationResponse from(TripLocation location, boolean duplicate) {
        return new TripLocationResponse(
                location.getId(),
                location.getLatitude(),
                location.getLongitude(),
                location.getRecordedAt(),
                location.getCity(),
                location.getSpeedKmh(),
                duplicate);
    }
}

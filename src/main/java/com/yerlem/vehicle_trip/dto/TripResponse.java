package com.yerlem.vehicle_trip.dto;

import com.yerlem.vehicle_trip.entity.Trip;
import com.yerlem.vehicle_trip.enums.TripStatus;

import java.math.BigDecimal;
import java.time.Instant;

/** Seyahat bilgisi (tahmini mesafe/süre/ücret dahil). */
public record TripResponse(
        Long id,
        TripStatus status,
        Long userId,
        Long vehicleId,
        String city,
        BigDecimal pricePerKm,
        Double startLatitude,
        Double startLongitude,
        Double destinationLatitude,
        Double destinationLongitude,
        BigDecimal estimatedDistanceKm,
        Integer estimatedDurationMin,
        BigDecimal estimatedFee,
        Instant createdAt,
        Instant startedAt) {

    public static TripResponse from(Trip trip) {
        return new TripResponse(
                trip.getId(),
                trip.getStatus(),
                trip.getUser().getId(),
                trip.getVehicle().getId(),
                trip.getCity(),
                trip.getPricePerKm(),
                trip.getStartLatitude(),
                trip.getStartLongitude(),
                trip.getDestinationLatitude(),
                trip.getDestinationLongitude(),
                trip.getEstimatedDistanceKm(),
                trip.getEstimatedDurationMin(),
                trip.getEstimatedFee(),
                trip.getCreatedAt(),
                trip.getStartedAt());
    }
}

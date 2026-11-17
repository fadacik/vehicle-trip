package com.yerlem.vehicle_trip.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** application.yml -> app.trip.* */
@ConfigurationProperties(prefix = "app.trip")
public record TripProperties(
        int averageSpeedKmh,
        int durationTolerancePercent,
        double roadFactor,
        double cityRadiusKm,
        double arrivalRadiusKm,
        int locationMaxFutureSeconds) {
}


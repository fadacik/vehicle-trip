package com.yerlem.vehicle_trip.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** application.yml -> app.speed-limit.* */
@ConfigurationProperties(prefix = "app.speed-limit")
public record SpeedLimitProperties(int cityKmh, int outsideKmh) {
}


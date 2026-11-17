package com.yerlem.vehicle_trip.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** application.yml -> app.otp.* */
@ConfigurationProperties(prefix = "app.otp")
public record OtpProperties(int length, int validityMinutes, boolean exposeInResponse) {
}

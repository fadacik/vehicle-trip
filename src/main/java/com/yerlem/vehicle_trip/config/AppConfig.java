package com.yerlem.vehicle_trip.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties({OtpProperties.class, TripProperties.class, SpeedLimitProperties.class,
        DamageProperties.class})
public class AppConfig {

    /** Zaman kaynagi bean olarak verilir; testlerde sabit saat enjekte edilebilir. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}

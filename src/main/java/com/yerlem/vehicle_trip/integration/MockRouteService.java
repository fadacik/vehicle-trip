package com.yerlem.vehicle_trip.integration;

import com.yerlem.vehicle_trip.config.TripProperties;
import com.yerlem.vehicle_trip.service.GeoUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * D3: Gerçek rota servisi yerine haversine üzerinden yaklaşık hesap.
 * Mesafe = haversine × road-factor (varsayılan 1.0); süre = ceil(km / average-speed-kmh × 60) dk.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MockRouteService implements RouteService {

    private final TripProperties tripProperties;

    @Override
    public RouteEstimate estimate(double startLatitude, double startLongitude,
                                  double destinationLatitude, double destinationLongitude) {
        double rawKm = GeoUtils.haversineKm(startLatitude, startLongitude, destinationLatitude, destinationLongitude);
        double factoredKm = rawKm * tripProperties.roadFactor();
        BigDecimal distanceKm = BigDecimal.valueOf(factoredKm).setScale(2, RoundingMode.HALF_UP);
        int durationMinutes = (int) Math.ceil(factoredKm / tripProperties.averageSpeedKmh() * 60);
        log.debug("Rota tahmini hesaplandı: mesafe={} km, süre={} dk", distanceKm, durationMinutes);
        return new RouteEstimate(distanceKm, durationMinutes);
    }
}

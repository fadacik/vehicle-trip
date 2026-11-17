package com.yerlem.vehicle_trip.integration;

import java.math.BigDecimal;

/** Rota tahmini için soyutlama. Gerçek sağlayıcı yerine MockRouteService kullanılır. */
public interface RouteService {

    /**
     * İki nokta arası tahmini rota hesabı.
     *
     * @param distanceKm       tahmini yol mesafesi (km, scale 2)
     * @param durationMinutes  tahmini süre (dakika)
     */
    RouteEstimate estimate(double startLatitude, double startLongitude,
                           double destinationLatitude, double destinationLongitude);

    record RouteEstimate(BigDecimal distanceKm, int durationMinutes) {
    }
}

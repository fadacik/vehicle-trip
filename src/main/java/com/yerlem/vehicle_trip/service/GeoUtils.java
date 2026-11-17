package com.yerlem.vehicle_trip.service;

/**
 * Haversine formülüyle iki koordinat arasındaki büyük daire mesafesini (km) hesaplar.
 * Stateless yardımcı sınıf — Spring bean'i değil.
 */
public final class GeoUtils {

    private static final double EARTH_RADIUS_KM = 6_371.0;

    private GeoUtils() { /* utility */ }

    /**
     * İki koordinat arasındaki mesafeyi km olarak döner.
     */
    public static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_KM * c;
    }
}


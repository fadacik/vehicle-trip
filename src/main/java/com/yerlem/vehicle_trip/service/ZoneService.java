package com.yerlem.vehicle_trip.service;

import com.yerlem.vehicle_trip.entity.VehicleZone;
import com.yerlem.vehicle_trip.repository.VehicleZoneRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Koordinat-alan ilişkisini kontrol eder.
 * Trip oluşturma (hedef alan içi mi?) ve hız limiti (şehir içi/dışı)
 * gibi farklı adımlardan yeniden kullanılır.
 */
@Service
@RequiredArgsConstructor
public class ZoneService {

    private final VehicleZoneRepository vehicleZoneRepository;

    /**
     * Verilen koordinat, aracın herhangi bir kullanım alanının
     * merkezine haversine ≤ radius_km ise true döner.
     */
    @Transactional(readOnly = true)
    public boolean isWithinAnyZone(Long vehicleId, double latitude, double longitude) {
        List<VehicleZone> zones = vehicleZoneRepository.findByVehicleId(vehicleId);
        return zones.stream().anyMatch(z ->
                GeoUtils.haversineKm(latitude, longitude,
                        z.getCenterLatitude(), z.getCenterLongitude())
                        <= z.getRadiusKm().doubleValue());
    }

    /**
     * D10: verilen koordinat, aracın herhangi bir alan MERKEZİNE haversine
     * mesafesi verilen yarıçapa (km) eşit veya küçükse true döner.
     * Şehir içi/dışı hız limiti seçiminde city-radius-km ile kullanılır;
     * eşik kodda sabit değil, yapılandırmadan gelir.
     */
    @Transactional(readOnly = true)
    public boolean isWithinCityRadius(Long vehicleId, double latitude, double longitude, double cityRadiusKm) {
        List<VehicleZone> zones = vehicleZoneRepository.findByVehicleId(vehicleId);
        return zones.stream().anyMatch(z ->
                GeoUtils.haversineKm(latitude, longitude,
                        z.getCenterLatitude(), z.getCenterLongitude())
                        <= cityRadiusKm);
    }
}


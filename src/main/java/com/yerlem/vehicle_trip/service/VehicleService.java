package com.yerlem.vehicle_trip.service;

import com.yerlem.vehicle_trip.dto.VehicleDetailResponse;
import com.yerlem.vehicle_trip.dto.VehicleResponse;
import com.yerlem.vehicle_trip.dto.VehicleZoneResponse;
import com.yerlem.vehicle_trip.entity.Vehicle;
import com.yerlem.vehicle_trip.entity.VehicleZone;
import com.yerlem.vehicle_trip.enums.TripStatus;
import com.yerlem.vehicle_trip.enums.VehicleStatus;
import com.yerlem.vehicle_trip.exception.ApiException;
import com.yerlem.vehicle_trip.repository.TripRepository;
import com.yerlem.vehicle_trip.repository.VehicleRepository;
import com.yerlem.vehicle_trip.repository.VehicleZoneRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class VehicleService {

    private final VehicleRepository vehicleRepository;
    private final VehicleZoneRepository vehicleZoneRepository;
    private final TripRepository tripRepository;

    @Transactional(readOnly = true)
    public List<VehicleResponse> listVehicles(VehicleStatus status) {
        List<Vehicle> vehicles = (status != null)
                ? vehicleRepository.findByStatus(status)
                : vehicleRepository.findAll();
        return vehicles.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public VehicleDetailResponse getVehicle(Long id) {
        Vehicle v = findOrThrow(id);
        List<VehicleZone> zones = vehicleZoneRepository.findByVehicleId(id);
        return toDetailResponse(v, zones);
    }

    /**
     * D11 kuralları (yalnızca manuel geçişler):
     * <ul>
     *   <li>AVAILABLE → MAINTENANCE (araçta aktif trip yoksa)</li>
     *   <li>MAINTENANCE → AVAILABLE (araçta aktif trip yoksa)</li>
     * </ul>
     * AVAILABLE↔IN_USE geçişleri yalnızca trip start/complete ile olur, burada yasaktır.
     */
    @Transactional
    public VehicleResponse changeStatus(Long id, VehicleStatus newStatus) {
        Vehicle vehicle = findOrThrow(id);
        VehicleStatus current = vehicle.getStatus();

        validateManualTransition(current, newStatus, id);

        vehicle.setStatus(newStatus);
        vehicleRepository.save(vehicle);
        log.info("Araç {} durumu {} → {} olarak değiştirildi.", id, current, newStatus);
        return toResponse(vehicle);
    }

    /**
     * D11: AVAILABLE → IN_USE geçişi YALNIZCA seyahat başlatmada (trip start) yapılır.
     * Bu metot geçişin tek noktasıdır; TripService aracın durumunu doğrudan değiştirmez.
     */
    @Transactional
    public void markInUse(Vehicle vehicle) {
        if (vehicle.getStatus() != VehicleStatus.AVAILABLE) {
            throw new ApiException(HttpStatus.CONFLICT, "VEHICLE_NOT_AVAILABLE",
                    "Araç şu anda seyahat için uygun değil: " + vehicle.getStatus() + ".");
        }
        vehicle.setStatus(VehicleStatus.IN_USE);
        vehicleRepository.save(vehicle);
        log.info("Araç {} seyahat için kullanıma alındı (AVAILABLE → IN_USE).", vehicle.getId());
    }

    /**
     * D11: IN_USE → MAINTENANCE geçişi YALNIZCA arıza bildiriminde yapılır; manuel
     * changeStatus bu geçişi reddeder. Araç zaten MAINTENANCE ise no-op'tur (aynı
     * seyahette ikinci arıza hatasız kaydedilir). Kilitli araç entity'si alır
     * (markInUse ile aynı pattern); geçişin tek noktasıdır.
     */
    @Transactional
    public void markMaintenanceDueToFault(Vehicle vehicle) {
        VehicleStatus current = vehicle.getStatus();
        if (current == VehicleStatus.MAINTENANCE) {
            log.debug("Araç {} zaten MAINTENANCE; durum değişmedi (ikinci arıza bildirimi).", vehicle.getId());
            return;
        }
        if (current == VehicleStatus.AVAILABLE) {
            log.warn("Araç {} AVAILABLE iken arıza bildirildi (aktif seyahatle tutarsız durum); MAINTENANCE yapıldı.",
                    vehicle.getId());
        }
        vehicle.setStatus(VehicleStatus.MAINTENANCE);
        vehicleRepository.save(vehicle);
        log.info("Araç {} arıza nedeniyle bakıma alındı ({} → MAINTENANCE).", vehicle.getId(), current);
    }

    /**
     * D5/D11: seyahat tamamlanınca araç kullanımı kapanır (tek nokta).
     * Konum son konuma güncellenir; kalan menzil = max(0, kalan − gerçekleşen mesafe)
     * (scale 2). Durum: IN_USE → AVAILABLE; MAINTENANCE KALIR (arıza varsa bakım
     * devam eder; konum/menzil yine güncellenir); AVAILABLE beklenmedik durum →
     * yalnızca log.warn, durum değişmez. Kilitli araç entity'si alır.
     */
    @Transactional
    public void completeTripUsage(Vehicle vehicle, double latitude, double longitude, BigDecimal distanceKm) {
        vehicle.setLatitude(latitude);
        vehicle.setLongitude(longitude);

        BigDecimal usedRange = distanceKm.setScale(2, RoundingMode.HALF_UP);
        BigDecimal remaining = vehicle.getRemainingRangeKm().subtract(usedRange);
        if (remaining.compareTo(BigDecimal.ZERO) < 0) {
            remaining = BigDecimal.ZERO.setScale(2);
        }
        vehicle.setRemainingRangeKm(remaining);

        VehicleStatus current = vehicle.getStatus();
        if (current == VehicleStatus.IN_USE) {
            vehicle.setStatus(VehicleStatus.AVAILABLE);
        } else if (current != VehicleStatus.MAINTENANCE) {
            log.warn("Araç {} tamamlamada {} durumunda (IN_USE bekleniyordu); durum değiştirilmedi.",
                    vehicle.getId(), current);
        }

        vehicleRepository.save(vehicle);
        log.info("Araç kullanımı kapandı. vehicleId={}, kalanMenzil={} km, durum={} → {}",
                vehicle.getId(), remaining, current, vehicle.getStatus());
    }

    /** Paket-içi erişim; diğer servislerden çağrılabilir. */
    Vehicle findOrThrow(Long id) {
        return vehicleRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "VEHICLE_NOT_FOUND", "Araç bulunamadı."));
    }

    private void validateManualTransition(VehicleStatus current, VehicleStatus target, Long vehicleId) {
        // Manuel geçişler: AVAILABLE ↔ MAINTENANCE
        boolean allowed = (current == VehicleStatus.AVAILABLE && target == VehicleStatus.MAINTENANCE)
                || (current == VehicleStatus.MAINTENANCE && target == VehicleStatus.AVAILABLE);

        if (!allowed) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_VEHICLE_TRANSITION",
                    String.format("Araç durumu %s → %s geçişi yapılamaz.", current, target));
        }

        // MAINTENANCE → AVAILABLE: aktif seyahat varsa engelle
        if (current == VehicleStatus.MAINTENANCE && target == VehicleStatus.AVAILABLE) {
            if (tripRepository.existsByVehicleIdAndStatus(vehicleId, TripStatus.ACTIVE)) {
                throw new ApiException(HttpStatus.CONFLICT, "INVALID_VEHICLE_TRANSITION",
                        "Araçta aktif seyahat var; önce seyahati tamamlayın.");
            }
        }
    }

    private VehicleResponse toResponse(Vehicle v) {
        return new VehicleResponse(
                v.getId(), v.getPlate(), v.getBrand(), v.getModel(), v.getCity(),
                v.getLatitude(), v.getLongitude(),
                v.getTotalRangeKm(), v.getRemainingRangeKm(), v.getStatus());
    }

    private VehicleDetailResponse toDetailResponse(Vehicle v, List<VehicleZone> zones) {
        List<VehicleZoneResponse> zoneList = zones.stream()
                .map(z -> new VehicleZoneResponse(
                        z.getId(), z.getName(),
                        z.getCenterLatitude(), z.getCenterLongitude(), z.getRadiusKm()))
                .toList();
        return new VehicleDetailResponse(
                v.getId(), v.getPlate(), v.getBrand(), v.getModel(), v.getCity(),
                v.getLatitude(), v.getLongitude(),
                v.getTotalRangeKm(), v.getRemainingRangeKm(), v.getStatus(), zoneList);
    }
}


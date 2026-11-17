package com.yerlem.vehicle_trip.service;

import com.yerlem.vehicle_trip.dto.FaultReportRequest;
import com.yerlem.vehicle_trip.dto.FaultReportResponse;
import com.yerlem.vehicle_trip.entity.FaultReport;
import com.yerlem.vehicle_trip.entity.Trip;
import com.yerlem.vehicle_trip.entity.Vehicle;
import com.yerlem.vehicle_trip.enums.FaultType;
import com.yerlem.vehicle_trip.enums.TripStatus;
import com.yerlem.vehicle_trip.exception.ApiException;
import com.yerlem.vehicle_trip.repository.FaultReportRepository;
import com.yerlem.vehicle_trip.repository.TripRepository;
import com.yerlem.vehicle_trip.repository.VehicleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Arıza bildirimi (Adım 12 / D11, D12, D18).
 *
 * D18 kilit sırası: ÖNCE trip (findByIdForUpdate), SONRA araç (findByIdForUpdate).
 * Tüm kontroller ve yazmalar kilitli nesneler üzerinden yapılır; faultType doğrulaması
 * (400 INVALID_FAULT_TYPE) kilit alınmadan önce fail-fast çalışır.
 * Trip ACTIVE kalır (D12): kullanıcı arıza sonrasında da seyahati tamamlayabilir.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FaultService {

    private final TripRepository tripRepository;
    private final VehicleRepository vehicleRepository;
    private final FaultReportRepository faultReportRepository;
    private final VehicleService vehicleService;
    private final Clock clock;

    /**
     * Arıza bildirir: fault_report kaydı yazar ve aracı MAINTENANCE yapar (tek nokta:
     * VehicleService.markMaintenanceDueToFault). Araç zaten MAINTENANCE ise yalnızca
     * rapor yazılır — aynı seyahette ikinci arıza hatasızdır.
     */
    @Transactional
    public FaultReportResponse reportFault(Long tripId, FaultReportRequest request) {
        FaultType faultType = parseFaultType(request.faultType());

        // D18: trip kilidi her zaman İLK alınır.
        Trip trip = tripRepository.findByIdForUpdate(tripId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TRIP_NOT_FOUND",
                        "Seyahat bulunamadı."));

        if (trip.getStatus() != TripStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRIP_STATE",
                    "Arıza yalnızca ACTIVE durumundaki seyahate bildirilebilir (mevcut durum: "
                            + trip.getStatus() + ").");
        }

        // D18: araç kilidi trip'ten SONRA alınır; kilit öncesi yalnızca vehicleId okunur.
        Vehicle vehicle = vehicleRepository.findByIdForUpdate(trip.getVehicle().getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "VEHICLE_NOT_FOUND",
                        "Araç bulunamadı."));

        vehicleService.markMaintenanceDueToFault(vehicle);

        FaultReport report = new FaultReport();
        report.setTrip(trip);
        report.setVehicle(vehicle);
        report.setFaultType(faultType);
        report.setDescription(request.description());
        report.setReportedAt(Instant.now(clock));
        FaultReport saved = faultReportRepository.save(report);

        log.info("Arıza bildirildi. tripId={}, vehicleId={}, tür={}", tripId, vehicle.getId(), faultType);
        return FaultReportResponse.from(saved, vehicle);
    }

    /** Seyahatin arıza bildirimlerini zaman sırasına (eski → yeni) göre döner. */
    @Transactional(readOnly = true)
    public List<FaultReportResponse> getFaults(Long tripId) {
        if (!tripRepository.existsById(tripId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TRIP_NOT_FOUND", "Seyahat bulunamadı.");
        }
        return faultReportRepository.findByTripIdOrderByReportedAtAsc(tripId).stream()
                .map(FaultReportResponse::from)
                .toList();
    }

    /**
     * faultType normalizasyonu: trim + Locale.ROOT ile büyük harf (varsayılan locale
     * KULLANILMAZ; örn. Türkçe locale'de "tire_blowout" → "TİRE_BLOWOUT" olurdu).
     * Geçersiz değer 400 INVALID_FAULT_TYPE; geçerli türler mesajda listelenir.
     */
    private FaultType parseFaultType(String raw) {
        String normalized = (raw == null) ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw invalidFaultType(raw);
        }
        try {
            return FaultType.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw invalidFaultType(raw);
        }
    }

    private ApiException invalidFaultType(String raw) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FAULT_TYPE",
                "Geçersiz arıza türü: " + raw + ". Geçerli türler: "
                        + Arrays.toString(FaultType.values()) + ".");
    }
}

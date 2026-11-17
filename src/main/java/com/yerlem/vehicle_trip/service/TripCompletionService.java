package com.yerlem.vehicle_trip.service;

import com.yerlem.vehicle_trip.config.TripProperties;
import com.yerlem.vehicle_trip.dto.CompleteTripRequest;
import com.yerlem.vehicle_trip.dto.TripSummaryResponse;
import com.yerlem.vehicle_trip.entity.Trip;
import com.yerlem.vehicle_trip.entity.TripFee;
import com.yerlem.vehicle_trip.entity.TripLocation;
import com.yerlem.vehicle_trip.entity.TripWarning;
import com.yerlem.vehicle_trip.entity.Vehicle;
import com.yerlem.vehicle_trip.enums.FeeStatus;
import com.yerlem.vehicle_trip.enums.FeeType;
import com.yerlem.vehicle_trip.enums.TripStatus;
import com.yerlem.vehicle_trip.enums.WarningType;
import com.yerlem.vehicle_trip.exception.ApiException;
import com.yerlem.vehicle_trip.repository.FaultReportRepository;
import com.yerlem.vehicle_trip.repository.TripFeeRepository;
import com.yerlem.vehicle_trip.repository.TripLocationRepository;
import com.yerlem.vehicle_trip.repository.TripRepository;
import com.yerlem.vehicle_trip.repository.TripWarningRepository;
import com.yerlem.vehicle_trip.repository.VehicleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Seyahat tamamlama ve tahsilat (Adım 13 / D7, D8, D13, D14, D15, D16, D18, D19).
 *
 * D18/D19 kilit sırası: trip (findByIdForUpdate) → araç (findByIdForUpdate) →
 * cüzdan (WalletService.charge içinde). Tüm akış TEK transaction'dır; tahsilat
 * başarısız olursa (402 PAYMENT_FAILED) exception yakalanmaz, tüm işlem geri
 * alınır, trip ACTIVE kalır ve istemci tekrar deneyebilir.
 *
 * Durum alanları (trip status/completedAt/actual*, araç durumu/konumu/menzili,
 * DURATION uyarısı) bilinçli olarak charge'dan SONRA değiştirilir: başarısız
 * tahsilatta nesneler kirletilmez.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TripCompletionService {

    private final TripRepository tripRepository;
    private final VehicleRepository vehicleRepository;
    private final TripLocationWriter tripLocationWriter;
    private final TripLocationRepository tripLocationRepository;
    private final TripFeeRepository tripFeeRepository;
    private final TripWarningRepository tripWarningRepository;
    private final FaultReportRepository faultReportRepository;
    private final WalletService walletService;
    private final DamageAssessmentService damageAssessmentService;
    private final VehicleService vehicleService;
    private final TripSummaryService tripSummaryService;
    private final TripProperties tripProperties;
    private final DoorCoordinator doorCoordinator;
    private final Clock clock;

    /**
     * Seyahati tamamlar: son konumu kaydeder, varış kuralını denetler, ücreti
     * tahsil eder, aracı serbest bırakır ve özeti döner. İdempotent'tir: trip
     * zaten COMPLETED ise hiçbir yazma yapmadan mevcut özet döner.
     */
    @Transactional
    public TripSummaryResponse completeTrip(Long tripId, CompleteTripRequest request) {
        // 1. D18: trip kilidi her zaman İLK alınır (eşzamanlı iki complete sıraya girer).
        Trip trip = tripRepository.findByIdForUpdate(tripId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TRIP_NOT_FOUND",
                        "Seyahat bulunamadı."));

        // 2. İdempotent: zaten COMPLETED → hiçbir yazma yapmadan mevcut özet.
        if (trip.getStatus() == TripStatus.COMPLETED) {
            return tripSummaryService.buildSummary(trip);
        }
        if (trip.getStatus() != TripStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRIP_STATE",
                    "Seyahat yalnızca ACTIVE durumundayken tamamlanabilir (mevcut durum: "
                            + trip.getStatus() + ").");
        }

        // 3. D18: araç kilidi trip'ten SONRA alınır; kilit öncesi yalnızca vehicleId okunur.
        //    Araç, appendLocation'ın trip.getVehicle() okumasından önce kilitli ve taze olur.
        Vehicle vehicle = vehicleRepository.findByIdForUpdate(trip.getVehicle().getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "VEHICLE_NOT_FOUND",
                        "Araç bulunamadı."));

        // 4. Son konum çekirdek üzerinden kaydedilir (kilit yok; doğrulama +
        //    mikrosaniye kırpma + hız uyarısı TripLocationWriter'da). INVALID_LOCATION
        //    hataları olduğu gibi yayılır.
        Instant recordedAt = request.recordedAt() != null ? request.recordedAt() : Instant.now(clock);
        TripLocation lastLocation = tripLocationWriter
                .appendLocation(trip, request.latitude(), request.longitude(), request.speedKmh(), recordedAt)
                .location();

        // 5. Varış kuralı (D14): arıza varsa atlanır.
        validateArrival(trip, lastLocation);

        // 6. Gerçekleşen mesafe (D16): ardışık haversine toplamı, yol katsayısı YOK.
        BigDecimal actualDistanceKm = calculateActualDistanceKm(trip);

        // 7. completedAt = max(sunucu saati, son konum zamanı); süre negatif olamaz.
        Instant now = Instant.now(clock);
        Instant completedAt = lastLocation.getRecordedAt().isAfter(now) ? lastLocation.getRecordedAt() : now;
        long actualSeconds = Math.max(0L, Duration.between(trip.getStartedAt(), completedAt).getSeconds());
        int actualDurationMin = (int) Math.ceil(actualSeconds / 60.0);

        // 8. Ücret kaydı (D7): tutar tahmin ücretidir, yeniden hesaplanmaz.
        TripFee fee = tripFeeRepository.findByTripIdAndFeeType(tripId, FeeType.DISTANCE)
                .orElseGet(() -> {
                    TripFee created = new TripFee();
                    created.setTrip(trip);
                    created.setAmount(trip.getEstimatedFee());
                    created.setCreatedAt(Instant.now(clock));
                    return tripFeeRepository.save(created);
                });

        // 8b. Adım 17 (D22): hasar değerlendirmesi — DISTANCE kaleminden sonra, tahsilattan ÖNCE.
        //     Bulgu yoksa 0.00 döner ve akış eskisiyle BİREBİR aynı kalır. İdempotent tekrar
        //     (COMPLETED erken dönüşü) buraya hiç ulaşmaz; assess ve tahsilat yeniden olmaz.
        BigDecimal damageTotal = damageAssessmentService.assess(trip);
        TripFee damageFee = null;
        if (damageTotal.compareTo(BigDecimal.ZERO) > 0) {
            damageFee = tripFeeRepository.findByTripIdAndFeeType(tripId, FeeType.DAMAGE)
                    .orElseGet(() -> {
                        TripFee created = new TripFee();
                        created.setTrip(trip);
                        created.setFeeType(FeeType.DAMAGE);
                        created.setAmount(damageTotal);
                        created.setCreatedAt(Instant.now(clock));
                        return tripFeeRepository.save(created);
                    });
        }

        // 9. Tahsilat (D8, D15): PAYMENT_FAILED yakalanmaz — exception tüm transaction'ı
        //    geri alır (TripDamage ve DAMAGE ücret satırı dahil), trip ACTIVE kalır,
        //    ikinci tahsilat oluşmaz. Adım 17: TEK TRIP_CHARGE = mesafe + hasar; kalemler
        //    trip_fees'te AYRI satırdır, hareket açıklamasında döküm taşınır.
        if (damageFee == null) {
            walletService.charge(trip, fee.getAmount());
        } else {
            walletService.charge(trip, fee.getAmount().add(damageFee.getAmount()).setScale(2, RoundingMode.HALF_UP),
                    "Seyahat ücreti tahsilatı (Seyahat #" + tripId + ") — mesafe: " + fee.getAmount()
                            + " TL + hasar: " + damageFee.getAmount() + " TL");
        }

        // 10. Ücretler tahsil edildi (tutar 0 olsa da; cüzdan hareketi yazmamayı WalletService yapar).
        fee.setStatus(FeeStatus.CHARGED);
        fee.setChargedAt(Instant.now(clock));
        if (damageFee != null) {
            damageFee.setStatus(FeeStatus.CHARGED);
            damageFee.setChargedAt(Instant.now(clock));
        }

        // 11. Süre uyarısı (D13) — tamsayı aritmetiği, eşitlikte uyarı yok.
        saveDurationWarningIfNeeded(trip, actualDurationMin);

        // 12. Araç kullanımı kapanır (D11 tek nokta): IN_USE → AVAILABLE; MAINTENANCE kalır
        //     (konum ve menzil yine güncellenir).
        vehicleService.completeTripUsage(vehicle, lastLocation.getLatitude(), lastLocation.getLongitude(),
                actualDistanceKm);

        // 13. Trip tamamlanır.
        trip.setActualDistanceKm(actualDistanceKm);
        trip.setActualDurationMin(actualDurationMin);
        trip.setCompletedAt(completedAt);
        trip.setStatus(TripStatus.COMPLETED);
        tripRepository.saveAndFlush(trip);

        // Adım 17 (D20): kapı kilitleme transaction'ın SON harici adımıdır (COMPLETED
        // saveAndFlush'tan sonra, özet üretiminden önce). Hata → 503; tahsilat dahil HER ŞEY
        // geri alınır (trip ACTIVE, araç IN_USE, son konum ve TRIP_CHARGE yok) — kullanıcı
        // tekrar denediğinde tek ve tam bir tahsilat oluşur. İdempotent tekrar buraya
        // ulaşmaz (yukarıda COMPLETED erken dönüşü); arızalı (MAINTENANCE) araç da kilitlenir.
        doorCoordinator.lockForTripCompletion(trip, vehicle);

        log.info("Seyahat tamamlandı. tripId={}, gerçekleşenMesafe={} km, gerçekleşenSüre={} dk, ücret={}",
                tripId, actualDistanceKm, actualDurationMin, fee.getAmount());

        // 14. Özet — GET summary ile AYNI üreticiden (kod kopyalama yok).
        return tripSummaryService.buildSummary(trip);
    }

    /** D14: arıza kaydı yoksa son konum başlangıca VEYA hedefe varış yarıçapı içinde olmalı. */
    private void validateArrival(Trip trip, TripLocation lastLocation) {
        if (faultReportRepository.existsByTripId(trip.getId())) {
            return; // D14 istisnası: arıza bildirilen seyahat her yerden tamamlanabilir.
        }
        double arrivalRadiusKm = tripProperties.arrivalRadiusKm();
        boolean atStart = GeoUtils.haversineKm(lastLocation.getLatitude(), lastLocation.getLongitude(),
                trip.getStartLatitude(), trip.getStartLongitude()) <= arrivalRadiusKm;
        boolean atDestination = GeoUtils.haversineKm(lastLocation.getLatitude(), lastLocation.getLongitude(),
                trip.getDestinationLatitude(), trip.getDestinationLongitude()) <= arrivalRadiusKm;
        if (!atStart && !atDestination) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NOT_AT_ENDPOINT",
                    "Son konum, başlangıç noktasına veya hedefe " + arrivalRadiusKm + " km içinde olmalıdır.");
        }
    }

    /** D16: noktalar = [trip başlangıcı] + zaman sıralı konumlar (son konum dahil). */
    private BigDecimal calculateActualDistanceKm(Trip trip) {
        double totalKm = 0.0;
        double previousLat = trip.getStartLatitude();
        double previousLng = trip.getStartLongitude();
        for (TripLocation location : tripLocationRepository.findByTripIdOrderByRecordedAtAsc(trip.getId())) {
            totalKm += GeoUtils.haversineKm(previousLat, previousLng, location.getLatitude(), location.getLongitude());
            previousLat = location.getLatitude();
            previousLng = location.getLongitude();
        }
        return BigDecimal.valueOf(totalKm).setScale(2, RoundingMode.HALF_UP);
    }

    /** D13: gerçekleşen süre, toleransı AŞARSA uyarı yazılır (eşitlikte uyarı yok). */
    private void saveDurationWarningIfNeeded(Trip trip, int actualDurationMin) {
        int estimatedDurationMin = trip.getEstimatedDurationMin();
        if (actualDurationMin * 100 > estimatedDurationMin * (100 + tripProperties.durationTolerancePercent())) {
            TripWarning warning = new TripWarning();
            warning.setTrip(trip);
            warning.setWarningType(WarningType.DURATION_EXCEEDED);
            warning.setMessage("Planlanan süre aşıldı: planlanan=" + estimatedDurationMin
                    + " dk, gerçekleşen=" + actualDurationMin + " dk, tolerans=%"
                    + tripProperties.durationTolerancePercent() + ".");
            warning.setCreatedAt(Instant.now(clock));
            tripWarningRepository.save(warning);
            log.warn("Planlanan süre aşıldı. tripId={}, planlanan={} dk, gerçekleşen={} dk",
                    trip.getId(), estimatedDurationMin, actualDurationMin);
        }
    }
}

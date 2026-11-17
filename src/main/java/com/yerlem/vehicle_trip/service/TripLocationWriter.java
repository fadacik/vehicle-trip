package com.yerlem.vehicle_trip.service;

import com.yerlem.vehicle_trip.config.SpeedLimitProperties;
import com.yerlem.vehicle_trip.config.TripProperties;
import com.yerlem.vehicle_trip.entity.Trip;
import com.yerlem.vehicle_trip.entity.TripLocation;
import com.yerlem.vehicle_trip.entity.TripWarning;
import com.yerlem.vehicle_trip.entity.Vehicle;
import com.yerlem.vehicle_trip.enums.TripStatus;
import com.yerlem.vehicle_trip.enums.WarningType;
import com.yerlem.vehicle_trip.exception.ApiException;
import com.yerlem.vehicle_trip.repository.TripLocationRepository;
import com.yerlem.vehicle_trip.repository.TripRepository;
import com.yerlem.vehicle_trip.repository.TripWarningRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Konum kaydının YAZMA birimi (Adım 11 / D9, D10).
 *
 * recordLocation: endpoint yolu — trip satırını D18 gereği İLK olarak kilitler
 * (findByIdForUpdate), sonra çekirdeği çağırır. Kilit, aynı recordedAt'li eşzamanlı
 * istekleri sıraya sokar ve tamamlama (Adım 13) ile çakışmayı engeller; unique kısıt
 * (uq_trip_locations_trip_time) yalnızca son savunmadır.
 *
 * appendLocation: ÇEKİRDEK — trip kilidinin çağıran tarafından ZATEN alındığını varsayar;
 * doğrulama + tekrar kontrolü + konum/uyarı yazımını yapar. Adım 13 (tamamlama)
 * bu metodu kendi transaction'ı içinde, trip kilidi zaten elindeyken yeniden kullanır.
 *
 * recordLocation → appendLocation iç çağrısı proxy'yi bypass eder ama güvenlidir:
 * appendLocation'ın REQUIRED yayılımı zaten "etkin transaction'a katıl" davranışıdır.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TripLocationWriter {

    private final TripRepository tripRepository;
    private final TripLocationRepository tripLocationRepository;
    private final TripWarningRepository tripWarningRepository;
    private final ZoneService zoneService;
    private final TripProperties tripProperties;
    private final SpeedLimitProperties speedLimitProperties;
    private final Clock clock;

    /** Konum kaydı sonucu: kayıt + tekrar (duplicate) bayrağı. */
    public record LocationRecordResult(TripLocation location, boolean duplicate) {
    }

    /**
     * Endpoint yolu: trip'i kilitler (D18: trip her zaman ilk kilit) ve çekirdeği
     * çalıştırır.
     * Konum ve varsa hız uyarısı bu metodun tek transaction'ında yazılır.
     */
    @Transactional
    public LocationRecordResult recordLocation(Long tripId, double latitude, double longitude,
            BigDecimal speedKmh, Instant recordedAt) {
        Trip trip = tripRepository.findByIdForUpdate(tripId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TRIP_NOT_FOUND",
                        "Seyahat bulunamadı."));
        return appendLocation(trip, latitude, longitude, speedKmh, recordedAt);
    }

    /**
     * ÇEKİRDEK (D9, D10). Doğrulama sırası: ACTIVE → lat/lng aralık → hız >= 0 →
     * başlangıçtan önce değil → TEKRAR kontrolü → son kayıttan eski değil →
     * gelecekte değil → yazım (+ hız limiti uyarısı).
     */
    @Transactional
    public LocationRecordResult appendLocation(Trip trip, double latitude, double longitude,
            BigDecimal speedKmh, Instant recordedAt) {
        if (trip.getStatus() != TripStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRIP_STATE",
                    "Konum yalnızca ACTIVE durumundaki seyahate eklenebilir (mevcut durum: "
                            + trip.getStatus() + ").");
        }

        // PostgreSQL mikrosaniye saklar; nanosaniyeli değer tekrar kontrolünü bozar (D9).
        // Normalizasyon burada (çekirdekte) yapılır ki Adım 13'ün doğrudan çağrıları da
        // aynı davranışı alsın.
        Instant normalized = recordedAt.truncatedTo(ChronoUnit.MICROS);

        if (latitude < -90 || latitude > 90) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_LOCATION",
                    "Enlem -90 ile 90 arasında olmalıdır: " + latitude + ".");
        }
        if (longitude < -180 || longitude > 180) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_LOCATION",
                    "Boylam -180 ile 180 arasında olmalıdır: " + longitude + ".");
        }
        if (speedKmh != null && speedKmh.compareTo(BigDecimal.ZERO) < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_LOCATION",
                    "Hız negatif olamaz: " + speedKmh + ".");
        }
        if (normalized.isBefore(trip.getStartedAt())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_LOCATION",
                    "Konum zamanı seyahat başlangıcından önce olamaz.");
        }

        // D9 tekrar: aynı recordedAt ile gelen istek idempotent'tir; yeni satır
        // eklenmez, uyarı yazılmaz.
        Optional<TripLocation> existing = tripLocationRepository.findByTripIdAndRecordedAt(trip.getId(),
                normalized);
        if (existing.isPresent()) {
            return new LocationRecordResult(existing.get(), true);
        }

        tripLocationRepository.findFirstByTripIdOrderByRecordedAtDesc(trip.getId())
                .ifPresent(last -> {
                    if (normalized.isBefore(last.getRecordedAt())) {
                        throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_LOCATION",
                                "Konum zamanı son kayıttan eski olamaz.");
                    }
                });

        if (normalized.isAfter(Instant.now(clock).plusSeconds(tripProperties.locationMaxFutureSeconds()))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_LOCATION",
                    "Konum zamanı sunucu saatinden " + tripProperties.locationMaxFutureSeconds()
                            + " saniyeden fazla ileride olamaz.");
        }

        // D10: konumun city alanı — aracın kullanım alanı (radius_km) içindeyse aracın
        // şehri, değilse null.
        Vehicle vehicle = trip.getVehicle();
        boolean withinZone = zoneService.isWithinAnyZone(vehicle.getId(), latitude, longitude);
        String city = withinZone ? vehicle.getCity() : null;

        // D10: hız limiti — alan merkezine city-radius-km içindeyse şehir içi limiti,
        // değilse şehir dışı.
        BigDecimal speedLimit = zoneService.isWithinCityRadius(vehicle.getId(), latitude, longitude,
                tripProperties.cityRadiusKm())
                        ? BigDecimal.valueOf(speedLimitProperties.cityKmh())
                        : BigDecimal.valueOf(speedLimitProperties.outsideKmh());

        TripLocation location = new TripLocation();
        location.setTrip(trip);
        location.setLatitude(latitude);
        location.setLongitude(longitude);
        location.setRecordedAt(normalized);
        location.setCity(city);
        location.setSpeedKmh(speedKmh);

        // saveAndFlush: olası unique kısıt ihlali burada yüzeye çıkar ve writer
        // transaction'ı geri döner;
        // dış katman (TripLocationService) ihvali yakalayıp duplicate yanıtına çevirir.
        TripLocation saved = tripLocationRepository.saveAndFlush(location);

        // D10: hız limitini AŞAN (eşit değil) her kayıt için SPEED_LIMIT uyarısı — aynı
        // transaction'da.
        if (speedKmh != null && speedKmh.compareTo(speedLimit) > 0) {
            TripWarning warning = new TripWarning();
            warning.setTrip(trip);
            warning.setWarningType(WarningType.SPEED_LIMIT);
            warning.setMessage("Hız limiti aşıldı: hız=" + speedKmh + " km/sa, limit=" + speedLimit
                    + " km/sa, kayıt zamanı=" + normalized + ".");
            warning.setCreatedAt(Instant.now(clock));
            tripWarningRepository.save(warning);
            log.warn("Hız limiti aşıldı. tripId={}, hız={} km/sa, limit={} km/sa, kayıtZamanı={}",
                    trip.getId(), speedKmh, speedLimit, normalized);
        }

        log.info("Konum kaydedildi. tripId={}, recordedAt={}, city={}, hız={} km/sa",
                trip.getId(), normalized, city, speedKmh);
        return new LocationRecordResult(saved, false);
    }
}

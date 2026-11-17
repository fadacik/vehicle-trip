package com.yerlem.vehicle_trip.service;

import com.yerlem.vehicle_trip.dto.LocationRecordRequest;
import com.yerlem.vehicle_trip.dto.TripLocationResponse;
import com.yerlem.vehicle_trip.dto.TripWarningResponse;
import com.yerlem.vehicle_trip.entity.Trip;
import com.yerlem.vehicle_trip.enums.TripStatus;
import com.yerlem.vehicle_trip.exception.ApiException;
import com.yerlem.vehicle_trip.repository.TripLocationRepository;
import com.yerlem.vehicle_trip.repository.TripRepository;
import com.yerlem.vehicle_trip.repository.TripWarningRepository;
import com.yerlem.vehicle_trip.service.TripLocationWriter.LocationRecordResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Konum kaydının DIŞ orkestrasyon katmanı (Adım 11 / D9).
 *
 * Bu sınıf bilinçli olarak @Transactional DEĞİLDİR: PostgreSQL'de bir statement hatası
 * transaction'ı rollback-only yapar; unique kısıt ihlalini yakalayıp aynı transaction
 * içinde 200 dönülemez (UnexpectedRollbackException). Yazma işi writer'ın kendi
 * transaction'ında gerçekleşir; ihlal burada (transaction dışında) yakalanır, kazanan
 * eşzamanlı isteğin satırı yeniden okunur ve duplicate yanıtı döner. Böylece 500 /
 * DATA_CONFLICT dışarı sızmaz.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TripLocationService {

    /** D9: eşzamanlı tekrar konum kaydında ihlal edilebilecek unique kısıt (son savunma). */
    private static final String CONSTRAINT_TRIP_LOCATION_TIME = "uq_trip_locations_trip_time";

    private final TripRepository tripRepository;
    private final TripLocationRepository tripLocationRepository;
    private final TripWarningRepository tripWarningRepository;
    private final TripLocationWriter tripLocationWriter;
    private final Clock clock;

    /**
     * Konum kaydeder. Hızlı yol kontrolleri (404 / 409) kilitsiz yapılır; yazma, writer'ın
     * trip satırını kilitlediği transaction'ında gerçekleşir. Yeni kayıt 201 + duplicate=false,
     * tekrar 200 + duplicate=true döner (durum kodu controller'da belirlenir).
     */
    public TripLocationResponse recordLocation(Long tripId, LocationRecordRequest request) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TRIP_NOT_FOUND", "Seyahat bulunamadı."));

        if (trip.getStatus() != TripStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRIP_STATE",
                    "Konum yalnızca ACTIVE durumundaki seyahate eklenebilir (mevcut durum: "
                            + trip.getStatus() + ").");
        }

        // Çözümleme + normalizasyon: recordedAt verilmezse Clock; PostgreSQL mikrosaniye sakladığı için
        // nanosaniye kırpılır. Çekirdek (appendLocation) da idempotent şekilde kırpar (Adım 13 için);
        // buradaki kırpma, kısıt ihlali sonrası yeniden okuma anahtarının DB'deki değerle eşleşmesini sağlar.
        Instant recordedAt = (request.recordedAt() != null ? request.recordedAt() : Instant.now(clock))
                .truncatedTo(ChronoUnit.MICROS);

        LocationRecordResult result;
        try {
            result = tripLocationWriter.recordLocation(tripId, request.latitude(), request.longitude(),
                    request.speedKmh(), recordedAt);
        } catch (DataIntegrityViolationException ex) {
            return handleDuplicateViolation(ex, tripId, recordedAt);
        }
        return TripLocationResponse.from(result.location(), result.duplicate());
    }

    /**
     * Son savunma: writer'ın transaction'ı unique kısıt ihlaliyle geri dönmüştür (kilit yarıda
     * kapatılmış bir yol). İhlal uq_trip_locations_trip_time ise kazanan eşzamanlı istektir;
     * mevcut satır yeniden okunup duplicate=true döner. Bilinmeyen kısıt veya satır bulunamazsa
     * orijinal istisna yeniden fırlatılır (yanlış bir başarı yanıtı üretilmez).
     */
    private TripLocationResponse handleDuplicateViolation(DataIntegrityViolationException ex,
                                                          Long tripId, Instant recordedAt) {
        if (ExceptionMessages.collect(ex).contains(CONSTRAINT_TRIP_LOCATION_TIME)) {
            return tripLocationRepository.findByTripIdAndRecordedAt(tripId, recordedAt)
                    .map(location -> {
                        log.info("Eşzamanlı tekrar konum kaydı duplicate olarak yanıtlandı. tripId={}", tripId);
                        return TripLocationResponse.from(location, true);
                    })
                    .orElseThrow(() -> ex);
        }
        throw ex;
    }

    /** Seyahatin konumlarını zaman sırasına (eski → yeni) göre döner. */
    @Transactional(readOnly = true)
    public List<TripLocationResponse> getLocations(Long tripId) {
        requireTrip(tripId);
        return tripLocationRepository.findByTripIdOrderByRecordedAtAsc(tripId)
                .stream()
                .map(location -> TripLocationResponse.from(location, false))
                .toList();
    }

    /** Seyahatin uyarılarını oluşturma zamanına göre döner. */
    @Transactional(readOnly = true)
    public List<TripWarningResponse> getWarnings(Long tripId) {
        requireTrip(tripId);
        return tripWarningRepository.findByTripIdOrderByCreatedAtAsc(tripId)
                .stream()
                .map(TripWarningResponse::from)
                .toList();
    }

    private void requireTrip(Long tripId) {
        if (!tripRepository.existsById(tripId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TRIP_NOT_FOUND", "Seyahat bulunamadı.");
        }
    }
}

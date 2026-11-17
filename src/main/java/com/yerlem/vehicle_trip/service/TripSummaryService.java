package com.yerlem.vehicle_trip.service;

import com.yerlem.vehicle_trip.dto.TimelineEventResponse;
import com.yerlem.vehicle_trip.dto.TripSummaryResponse;
import com.yerlem.vehicle_trip.entity.FaultReport;
import com.yerlem.vehicle_trip.entity.Trip;
import com.yerlem.vehicle_trip.entity.TripDamage;
import com.yerlem.vehicle_trip.entity.TripDoorAction;
import com.yerlem.vehicle_trip.entity.TripFee;
import com.yerlem.vehicle_trip.entity.TripPhoto;
import com.yerlem.vehicle_trip.entity.TripWarning;
import com.yerlem.vehicle_trip.enums.FeeType;
import com.yerlem.vehicle_trip.enums.TripStatus;
import com.yerlem.vehicle_trip.enums.WarningType;
import com.yerlem.vehicle_trip.exception.ApiException;
import com.yerlem.vehicle_trip.repository.FaultReportRepository;
import com.yerlem.vehicle_trip.repository.TripDamageRepository;
import com.yerlem.vehicle_trip.repository.TripDoorActionRepository;
import com.yerlem.vehicle_trip.repository.TripFeeRepository;
import com.yerlem.vehicle_trip.repository.TripLocationRepository;
import com.yerlem.vehicle_trip.repository.TripPhotoRepository;
import com.yerlem.vehicle_trip.repository.TripRepository;
import com.yerlem.vehicle_trip.repository.TripWarningRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Seyahat özeti ve zaman çizelgesi (Adım 13, 1. kısım).
 *
 * Özet TEK üreticiden çıkar: {@link #buildSummary(Trip)} public'tir ve hem
 * GET /summary hem de (Adım 13'ün 2. kısmında) completeTrip yanıtı AYNI metodu
 * çağırır — kod kopyalama yok. completeTrip, buildSummary'yi kendi yazma
 * transaction'ı içinde çağıracak; lazy user/vehicle alanlarına eriştiği için
 * aktif bir transaction şarttır.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TripSummaryService {

    /**
     * Eşit zamanlı olaylarda sabit tür sırası (Adım 17 onaylı sıralama; mevcut beş olayın
     * kendi aralarındaki sıra korunur): CREATED → STARTED → DOOR_UNLOCKED → PHOTO_ADDED →
     * FAULT → WARNING → DOOR_ACTION_FAILED → DOOR_COMPENSATED → DAMAGE_DETECTED →
     * DOOR_LOCKED → COMPLETED.
     */
    private static final List<String> EVENT_TYPE_ORDER = List.of(
            "TRIP_CREATED", "TRIP_STARTED", "DOOR_UNLOCKED", "PHOTO_ADDED", "FAULT_REPORTED", "WARNING",
            "DOOR_ACTION_FAILED", "DOOR_COMPENSATED", "DAMAGE_DETECTED", "DOOR_LOCKED", "TRIP_COMPLETED");

    private final TripRepository tripRepository;
    private final TripFeeRepository tripFeeRepository;
    private final TripWarningRepository tripWarningRepository;
    private final FaultReportRepository faultReportRepository;
    private final TripLocationRepository tripLocationRepository;
    private final TripDoorActionRepository tripDoorActionRepository;
    private final TripPhotoRepository tripPhotoRepository;
    private final TripDamageRepository tripDamageRepository;

    /** Seyahat özeti: her trip durumunda döner (404 yalnızca trip yoksa). */
    @Transactional(readOnly = true)
    public TripSummaryResponse getSummary(Long tripId) {
        Trip trip = findTrip(tripId);
        return buildSummary(trip);
    }

    /** Zaman çizelgesi: olaylar zaman sırasıyla; eşit zamanda tür sırası belirlidir. */
    @Transactional(readOnly = true)
    public List<TimelineEventResponse> getTimeline(Long tripId) {
        Trip trip = findTrip(tripId);
        return buildTimeline(trip);
    }

    /**
     * Tek özet üretici: GET summary ve completeTrip ortak kullanır. Çağıranın
     * aktif transaction'ı içinde çalışmalıdır (lazy alanlar + aynı transaction'da
     * yazılan ücret/uyarı kayıtlarının sorgularda görünmesi).
     */
    public TripSummaryResponse buildSummary(Trip trip) {
        Long tripId = trip.getId();
        TripFee distanceFee = tripFeeRepository.findByTripIdAndFeeType(tripId, FeeType.DISTANCE)
                .orElse(null);
        TripFee damageFee = tripFeeRepository.findByTripIdAndFeeType(tripId, FeeType.DAMAGE)
                .orElse(null);
        List<TripWarning> warnings = tripWarningRepository.findByTripIdOrderByCreatedAtAsc(tripId);
        List<FaultReport> faults = faultReportRepository.findByTripIdOrderByReportedAtAsc(tripId);
        // Adım 14 Parça 4: locationCount için konumların TAMAMI yüklenmez; sayı count sorgusuyla alınır
        // (özet zaten yalnızca adet istiyor; liste + entity yüklemek N+1/ bellek israfıydı).
        int locationCount = (int) tripLocationRepository.countByTripId(tripId);
        List<TripDoorAction> doorActions = tripDoorActionRepository.findByTripIdOrderByCreatedAtAsc(tripId);
        List<TripPhoto> photos = tripPhotoRepository.findByTripIdOrderByPhaseAscPhotoTypeAsc(tripId);
        List<TripDamage> damages = tripDamageRepository.findByTripIdOrderByPhotoTypeAsc(tripId);
        String delayExplanation = buildDelayExplanation(trip, warnings, faults);
        return TripSummaryResponse.from(trip, distanceFee, damageFee, warnings, faults, doorActions, photos,
                damages, locationCount, delayExplanation);
    }

    /** delayExplanation (belirlenimci, Türkçe): yalnızca COMPLETED seyahatte üretilir. */
    private String buildDelayExplanation(Trip trip, List<TripWarning> warnings, List<FaultReport> faults) {
        if (trip.getStatus() != TripStatus.COMPLETED) {
            return null;
        }
        boolean durationExceeded = warnings.stream()
                .anyMatch(warning -> warning.getWarningType() == WarningType.DURATION_EXCEEDED);
        if (!durationExceeded) {
            return "Seyahat süresi tolerans içinde tamamlandı.";
        }
        if (faults.isEmpty()) {
            return "Planlanan süre aşıldı; kayıtlı arıza yok.";
        }
        String faultList = faults.stream()
                .map(fault -> fault.getFaultType() + " (" + fault.getReportedAt() + ")")
                .collect(Collectors.joining(", "));
        return "Planlanan süre aşıldı; seyahat sırasında " + faults.size() + " arıza bildirildi: "
                + faultList + ". Gecikme büyük olasılıkla bu arızalardan kaynaklandı.";
    }

    /** Olayları zaman sırasına dizer; eşit zamanda sabit tür sırası geçerlidir. */
    private List<TimelineEventResponse> buildTimeline(Trip trip) {
        Long tripId = trip.getId();
        List<TimelineEventResponse> events = new ArrayList<>();
        events.add(TimelineEventResponse.tripCreated(trip.getCreatedAt()));
        if (trip.getStartedAt() != null) {
            events.add(TimelineEventResponse.tripStarted(trip.getStartedAt()));
        }
        faultReportRepository.findByTripIdOrderByReportedAtAsc(tripId)
                .forEach(report -> events.add(TimelineEventResponse.faultReported(report)));
        tripWarningRepository.findByTripIdOrderByCreatedAtAsc(tripId)
                .forEach(warning -> events.add(TimelineEventResponse.warning(warning)));
        tripDoorActionRepository.findByTripIdOrderByCreatedAtAsc(tripId)
                .forEach(action -> events.add(TimelineEventResponse.doorAction(action)));
        tripPhotoRepository.findByTripIdOrderByPhaseAscPhotoTypeAsc(tripId)
                .forEach(photo -> events.add(TimelineEventResponse.photoAdded(photo)));
        tripDamageRepository.findByTripIdOrderByPhotoTypeAsc(tripId)
                .forEach(damage -> events.add(TimelineEventResponse.damageDetected(damage)));
        if (trip.getCompletedAt() != null) {
            events.add(TimelineEventResponse.tripCompleted(trip.getCompletedAt()));
        }
        events.sort(Comparator
                .comparing(TimelineEventResponse::timestamp)
                .thenComparing(event -> EVENT_TYPE_ORDER.indexOf(event.eventType())));
        return events;
    }

    private Trip findTrip(Long tripId) {
        return tripRepository.findById(tripId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TRIP_NOT_FOUND",
                        "Seyahat bulunamadı."));
    }
}

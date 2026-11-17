package com.yerlem.vehicle_trip.service;

import com.yerlem.vehicle_trip.dto.TimelineEventResponse;
import com.yerlem.vehicle_trip.dto.TripSummaryResponse;
import com.yerlem.vehicle_trip.entity.FaultReport;
import com.yerlem.vehicle_trip.entity.Trip;
import com.yerlem.vehicle_trip.entity.TripFee;
import com.yerlem.vehicle_trip.entity.TripWarning;
import com.yerlem.vehicle_trip.entity.User;
import com.yerlem.vehicle_trip.entity.Vehicle;
import com.yerlem.vehicle_trip.enums.FeeStatus;
import com.yerlem.vehicle_trip.enums.FeeType;
import com.yerlem.vehicle_trip.enums.FaultType;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Adım 13 (1. kısım) — özet ve zaman çizelgesi.
 * delayExplanation 4 durumu, timeline sıralama + eşit zaman sırası,
 * trip yok 404, fee yoksa null.
 */
@ExtendWith(MockitoExtension.class)
class TripSummaryServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
    private static final Long TRIP_ID = 10L;
    private static final Long USER_ID = 1L;
    private static final Long VEHICLE_ID = 2L;

    @Mock
    private TripRepository tripRepository;
    @Mock
    private TripFeeRepository tripFeeRepository;
    @Mock
    private TripWarningRepository tripWarningRepository;
    @Mock
    private FaultReportRepository faultReportRepository;
    @Mock
    private TripLocationRepository tripLocationRepository;
    @Mock
    private TripDoorActionRepository tripDoorActionRepository;
    @Mock
    private TripPhotoRepository tripPhotoRepository;
    @Mock
    private TripDamageRepository tripDamageRepository;

    private TripSummaryService tripSummaryService;
    private Trip trip;

    @BeforeEach
    void setUp() {
        tripSummaryService = new TripSummaryService(tripRepository, tripFeeRepository,
                tripWarningRepository, faultReportRepository, tripLocationRepository, tripDoorActionRepository,
                tripPhotoRepository, tripDamageRepository);

        User user = new User();
        user.setId(USER_ID);
        Vehicle vehicle = new Vehicle();
        vehicle.setId(VEHICLE_ID);

        trip = new Trip();
        trip.setId(TRIP_ID);
        trip.setUser(user);
        trip.setVehicle(vehicle);
        trip.setStatus(TripStatus.COMPLETED);
        trip.setCity("İstanbul");
        trip.setPricePerKm(new BigDecimal("15.00"));
        trip.setEstimatedDistanceKm(new BigDecimal("6.13"));
        trip.setEstimatedDurationMin(10);
        trip.setEstimatedFee(new BigDecimal("91.95"));
        trip.setActualDistanceKm(new BigDecimal("10.87"));
        trip.setActualDurationMin(13);
        trip.setCreatedAt(NOW.minusSeconds(1200)); // 09:40
        trip.setStartedAt(NOW.minusSeconds(600));  // 09:50
        trip.setCompletedAt(NOW);                  // 10:00
    }

    // --- delayExplanation: 1. COMPLETED değil → null (uyarı olsa bile) ---
    @Test
    @DisplayName("Trip COMPLETED değilse delayExplanation null (DURATION_EXCEEDED uyarısı olsa bile)")
    void summary_tripNotCompleted_delayExplanationNull() {
        trip.setStatus(TripStatus.ACTIVE);
        stubSummary(null, List.of(warning(31L, WarningType.DURATION_EXCEEDED, NOW)),
                List.of(fault(21L, FaultType.BATTERY, NOW.minusSeconds(300))), 1);

        TripSummaryResponse summary = tripSummaryService.getSummary(TRIP_ID);

        assertThat(summary.delayExplanation()).isNull();
    }

    // --- delayExplanation: 2. tolerans içinde (DURATION_EXCEEDED yok) ---
    @Test
    @DisplayName("DURATION_EXCEEDED uyarısı yoksa: \"Seyahat süresi tolerans içinde tamamlandı.\"")
    void summary_withinTolerance_explanation() {
        stubSummary(null, List.of(warning(31L, WarningType.SPEED_LIMIT, NOW.minusSeconds(300))),
                List.of(), 2);

        TripSummaryResponse summary = tripSummaryService.getSummary(TRIP_ID);

        assertThat(summary.delayExplanation()).isEqualTo("Seyahat süresi tolerans içinde tamamlandı.");
    }

    // --- delayExplanation: 3. uyarı var, arıza yok ---
    @Test
    @DisplayName("DURATION_EXCEEDED var, arıza yoksa: \"Planlanan süre aşıldı; kayıtlı arıza yok.\"")
    void summary_durationExceeded_noFaults_explanation() {
        stubSummary(null, List.of(warning(31L, WarningType.DURATION_EXCEEDED, NOW.minusSeconds(300))),
                List.of(), 0);

        TripSummaryResponse summary = tripSummaryService.getSummary(TRIP_ID);

        assertThat(summary.delayExplanation()).isEqualTo("Planlanan süre aşıldı; kayıtlı arıza yok.");
    }

    // --- delayExplanation: 4. uyarı var, arıza var (metinde tür adı geçer) ---
    @Test
    @DisplayName("DURATION_EXCEEDED + arızalar: metinde arıza sayısı, tür adları ve sonuç cümlesi geçer")
    void summary_durationExceeded_withFaults_explanation() {
        stubSummary(null, List.of(warning(31L, WarningType.DURATION_EXCEEDED, NOW.minusSeconds(300))),
                List.of(
                        fault(21L, FaultType.TIRE_BLOWOUT, NOW.minusSeconds(600)),
                        fault(22L, FaultType.BATTERY, NOW.minusSeconds(300))),
                0);

        TripSummaryResponse summary = tripSummaryService.getSummary(TRIP_ID);

        assertThat(summary.delayExplanation())
                .contains("2 arıza bildirildi")
                .contains("TIRE_BLOWOUT (")
                .contains("BATTERY (")
                .contains("Gecikme büyük olasılıkla bu arızalardan kaynaklandı.");
    }

    // --- fee yoksa null; alan eşlemeleri ---
    @Test
    @DisplayName("DISTANCE ücreti yoksa fee null; trip alanları ve boş listeler doğru eşlenir")
    void summary_feeAbsent_null_fieldsMapped() {
        stubSummary(null, List.of(), List.of(), 3);

        TripSummaryResponse summary = tripSummaryService.getSummary(TRIP_ID);

        assertThat(summary.fee()).isNull();
        assertThat(summary.tripId()).isEqualTo(TRIP_ID);
        assertThat(summary.userId()).isEqualTo(USER_ID);
        assertThat(summary.vehicleId()).isEqualTo(VEHICLE_ID);
        assertThat(summary.status()).isEqualTo(TripStatus.COMPLETED);
        assertThat(summary.city()).isEqualTo("İstanbul");
        assertThat(summary.pricePerKm()).isEqualByComparingTo("15.00");
        assertThat(summary.estimatedDistanceKm()).isEqualByComparingTo("6.13");
        assertThat(summary.actualDistanceKm()).isEqualByComparingTo("10.87");
        assertThat(summary.estimatedDurationMin()).isEqualTo(10);
        assertThat(summary.actualDurationMin()).isEqualTo(13);
        assertThat(summary.estimatedFee()).isEqualByComparingTo("91.95");
        assertThat(summary.startedAt()).isEqualTo(NOW.minusSeconds(600));
        assertThat(summary.completedAt()).isEqualTo(NOW);
        assertThat(summary.locationCount()).isEqualTo(3);
        assertThat(summary.warnings()).isEmpty();
        assertThat(summary.faults()).isEmpty();
    }

    // --- fee/uyarı/arıza listeleri doğru eşlenir ---
    @Test
    @DisplayName("fee, warnings ve faults listeleri iç record'lara doğru eşlenir")
    void summary_feeWarningsFaults_mapped() {
        TripFee fee = new TripFee();
        fee.setAmount(new BigDecimal("91.95"));
        fee.setStatus(FeeStatus.CHARGED);
        fee.setChargedAt(NOW);
        stubSummary(fee,
                List.of(warning(31L, WarningType.DURATION_EXCEEDED, NOW.minusSeconds(300))),
                List.of(fault(21L, FaultType.TIRE_BLOWOUT, NOW.minusSeconds(600))),
                2);

        TripSummaryResponse summary = tripSummaryService.getSummary(TRIP_ID);

        assertThat(summary.fee().amount()).isEqualByComparingTo("91.95");
        assertThat(summary.fee().status()).isEqualTo(FeeStatus.CHARGED);
        assertThat(summary.fee().chargedAt()).isEqualTo(NOW);
        assertThat(summary.warnings()).hasSize(1);
        assertThat(summary.warnings().get(0).id()).isEqualTo(31L);
        assertThat(summary.warnings().get(0).warningType()).isEqualTo(WarningType.DURATION_EXCEEDED);
        assertThat(summary.warnings().get(0).message()).isEqualTo("mesaj 31");
        assertThat(summary.faults()).hasSize(1);
        assertThat(summary.faults().get(0).id()).isEqualTo(21L);
        assertThat(summary.faults().get(0).faultType()).isEqualTo(FaultType.TIRE_BLOWOUT);
        assertThat(summary.faults().get(0).description()).isEqualTo("açıklama 21");
        assertThat(summary.faults().get(0).reportedAt()).isEqualTo(NOW.minusSeconds(600));
    }

    // --- trip yok → 404 (summary) ---
    @Test
    @DisplayName("GET summary: olmayan trip → 404 TRIP_NOT_FOUND")
    void summary_tripNotFound_404() {
        when(tripRepository.findById(TRIP_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tripSummaryService.getSummary(TRIP_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(apiEx.getCode()).isEqualTo("TRIP_NOT_FOUND");
                });
    }

    // --- timeline: zaman sırası + eşit zamanda tür sırası (fault 10:00 = completedAt) ---
    @Test
    @DisplayName("GET timeline: olaylar zaman sırasıyla; eşit zamanda FAULT_REPORTED, TRIP_COMPLETED'ten önce")
    void timeline_sortedByTimestamp_tieBrokenByTypeOrder() {
        stubTimeline(
                List.of(fault(21L, FaultType.BATTERY, NOW)),                            // 10:00
                List.of(warning(31L, WarningType.SPEED_LIMIT, NOW.minusSeconds(300)))); // 09:55

        List<TimelineEventResponse> events = tripSummaryService.getTimeline(TRIP_ID);

        assertThat(events).extracting(TimelineEventResponse::eventType)
                .containsExactly("TRIP_CREATED", "TRIP_STARTED", "WARNING", "FAULT_REPORTED",
                        "TRIP_COMPLETED");
        assertThat(events.get(2).type()).isEqualTo("SPEED_LIMIT");
        assertThat(events.get(2).message()).isEqualTo("mesaj 31");
        assertThat(events.get(3).type()).isEqualTo("BATTERY");
        assertThat(events.get(3).message()).isEqualTo("açıklama 21");
        assertThat(events.get(4).timestamp()).isEqualTo(NOW);
    }

    // --- timeline: tüm olaylar aynı anda → sabit tür sırası ---
    @Test
    @DisplayName("GET timeline: eşit zamanlı olaylarda sıra TRIP_CREATED, TRIP_STARTED, FAULT_REPORTED, WARNING, TRIP_COMPLETED")
    void timeline_equalTimestamps_fixedTypeOrder() {
        trip.setCreatedAt(NOW);
        trip.setStartedAt(NOW);
        trip.setCompletedAt(NOW);
        stubTimeline(
                List.of(fault(21L, FaultType.ENGINE, NOW)),
                List.of(warning(31L, WarningType.DURATION_EXCEEDED, NOW)));

        List<TimelineEventResponse> events = tripSummaryService.getTimeline(TRIP_ID);

        assertThat(events).extracting(TimelineEventResponse::eventType)
                .containsExactly("TRIP_CREATED", "TRIP_STARTED", "FAULT_REPORTED", "WARNING",
                        "TRIP_COMPLETED");
        assertThat(events).extracting(TimelineEventResponse::timestamp)
                .containsOnly(NOW);
    }

    // --- timeline: başlamamış/tamamlanmamış trip → yalnızca mevcut olaylar ---
    @Test
    @DisplayName("GET timeline: startedAt/completedAt yoksa TRIP_STARTED/TRIP_COMPLETED olayı oluşmaz")
    void timeline_notStartedNotCompleted_optionalEventsAbsent() {
        trip.setStatus(TripStatus.CREATED);
        trip.setStartedAt(null);
        trip.setCompletedAt(null);
        stubTimeline(List.of(), List.of());

        List<TimelineEventResponse> events = tripSummaryService.getTimeline(TRIP_ID);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).eventType()).isEqualTo("TRIP_CREATED");
        assertThat(events.get(0).timestamp()).isEqualTo(NOW.minusSeconds(1200));
        assertThat(events.get(0).type()).isNull();
        assertThat(events.get(0).message()).isNull();
    }

    // --- trip yok → 404 (timeline) ---
    @Test
    @DisplayName("GET timeline: olmayan trip → 404 TRIP_NOT_FOUND")
    void timeline_tripNotFound_404() {
        when(tripRepository.findById(TRIP_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tripSummaryService.getTimeline(TRIP_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(apiEx.getCode()).isEqualTo("TRIP_NOT_FOUND");
                });
    }

    // --- yardımcılar ---

    private void stubSummary(TripFee fee, List<TripWarning> warnings, List<FaultReport> faults,
                             int locationCount) {
        when(tripRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip));
        when(tripFeeRepository.findByTripIdAndFeeType(TRIP_ID, FeeType.DISTANCE))
                .thenReturn(Optional.ofNullable(fee));
        when(tripWarningRepository.findByTripIdOrderByCreatedAtAsc(TRIP_ID)).thenReturn(warnings);
        when(faultReportRepository.findByTripIdOrderByReportedAtAsc(TRIP_ID)).thenReturn(faults);
        // Adım 14 Parça 4: buildSummary artık konumları listelemiyor, adet için countByTripId çağırıyor.
        when(tripLocationRepository.countByTripId(TRIP_ID)).thenReturn((long) locationCount);
    }

    private void stubTimeline(List<FaultReport> faults, List<TripWarning> warnings) {
        when(tripRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip));
        when(faultReportRepository.findByTripIdOrderByReportedAtAsc(TRIP_ID)).thenReturn(faults);
        when(tripWarningRepository.findByTripIdOrderByCreatedAtAsc(TRIP_ID)).thenReturn(warnings);
    }

    private TripWarning warning(Long id, WarningType type, Instant createdAt) {
        TripWarning warning = new TripWarning();
        warning.setId(id);
        warning.setWarningType(type);
        warning.setMessage("mesaj " + id);
        warning.setCreatedAt(createdAt);
        return warning;
    }

    private FaultReport fault(Long id, FaultType type, Instant reportedAt) {
        FaultReport report = new FaultReport();
        report.setId(id);
        report.setFaultType(type);
        report.setDescription("açıklama " + id);
        report.setReportedAt(reportedAt);
        return report;
    }
}

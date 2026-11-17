package com.yerlem.vehicle_trip.service;

import com.yerlem.vehicle_trip.config.TripProperties;
import com.yerlem.vehicle_trip.dto.CompleteTripRequest;
import com.yerlem.vehicle_trip.dto.TripSummaryResponse;
import com.yerlem.vehicle_trip.entity.Trip;
import com.yerlem.vehicle_trip.entity.TripFee;
import com.yerlem.vehicle_trip.entity.TripLocation;
import com.yerlem.vehicle_trip.entity.TripWarning;
import com.yerlem.vehicle_trip.entity.User;
import com.yerlem.vehicle_trip.entity.Vehicle;
import com.yerlem.vehicle_trip.enums.FeeStatus;
import com.yerlem.vehicle_trip.enums.FeeType;
import com.yerlem.vehicle_trip.enums.TripStatus;
import com.yerlem.vehicle_trip.enums.VehicleStatus;
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
import com.yerlem.vehicle_trip.repository.VehicleRepository;
import com.yerlem.vehicle_trip.repository.VehicleZoneRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Adım 13 (2. kısım) — seyahat tamamlama ve tahsilat (D7, D8, D13, D14, D15, D16, D18, D19).
 *
 * Coğrafi hesap GERÇEK GeoUtils üzerinden yapılır; mesafeler formülden türetilmemiş,
 * bağımsız hesaplanmış literal değerlerdir:
 *   Taksim (41.0370, 28.9850) -> Şişli (41.0602, 28.9877) = 2.5896 km
 *   Şişli -> Kadıköy (40.9919, 29.0270)                  = 8.2793 km
 *   toplam                                               = 10.8689 -> 10.87 km (menzil 280.00-10.87=269.13)
 *   Kadıköy'e 0.345 km (40.9950, 29.0270) = varış İÇİ ; 1.457 km (41.0050, 29.0270) = varış DIŞI
 *   Pendik (40.8780, 29.2330): başlangıca 27.32 km, hedefe 21.44 km (varış DIŞI)
 */
@ExtendWith(MockitoExtension.class)
class TripCompletionServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
    private static final Long TRIP_ID = 10L;
    private static final Long VEHICLE_ID = 2L;

    private static final double TAKSIM_LAT = 41.0370, TAKSIM_LNG = 28.9850;
    private static final double KADIKOY_LAT = 40.9919, KADIKOY_LNG = 29.0270;
    private static final double SISLI_LAT = 41.0602, SISLI_LNG = 28.9877;
    private static final double ARRIVAL_IN_LAT = 40.9950, ARRIVAL_IN_LNG = 29.0270;   // Kadıköy'e 0.345 km
    private static final double ARRIVAL_OUT_LAT = 41.0050, ARRIVAL_OUT_LNG = 29.0270; // Kadıköy'e 1.457 km
    private static final double PENDIK_LAT = 40.8780, PENDIK_LNG = 29.2330;

    @Mock
    private TripRepository tripRepository;
    @Mock
    private VehicleRepository vehicleRepository;
    @Mock
    private TripLocationWriter tripLocationWriter;
    @Mock
    private TripLocationRepository tripLocationRepository;
    @Mock
    private TripFeeRepository tripFeeRepository;
    @Mock
    private TripWarningRepository tripWarningRepository;
    @Mock
    private FaultReportRepository faultReportRepository;
    @Mock
    private WalletService walletService;
    @Mock
    private DamageAssessmentService damageAssessmentService;
    @Mock
    private VehicleZoneRepository vehicleZoneRepository;
    @Mock
    private TripDoorActionRepository tripDoorActionRepository;
    @Mock
    private TripPhotoRepository tripPhotoRepository;
    @Mock
    private TripDamageRepository tripDamageRepository;
    @Mock
    private DoorCoordinator doorCoordinator;

    private TripCompletionService service;
    private Trip trip;
    private Vehicle vehicle;
    private TripFee distanceFee;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        TripProperties tripProperties = new TripProperties(40, 20, 1.0, 15.0, 1.0, 60);
        // Gerçek VehicleService: durum/menzil/konum geçişleri davranışsal olarak doğrulanır (D11 tek nokta)
        VehicleService vehicleService = new VehicleService(vehicleRepository, vehicleZoneRepository, tripRepository);
        // Gerçek TripSummaryService: completeTrip AYNI özet üreticiyi kullanır (kopya yok) — mock repo'larla
        TripSummaryService tripSummaryService = new TripSummaryService(tripRepository, tripFeeRepository,
                tripWarningRepository, faultReportRepository, tripLocationRepository, tripDoorActionRepository,
                tripPhotoRepository, tripDamageRepository);
        service = new TripCompletionService(tripRepository, vehicleRepository, tripLocationWriter,
                tripLocationRepository, tripFeeRepository, tripWarningRepository, faultReportRepository,
                walletService, damageAssessmentService, vehicleService, tripSummaryService, tripProperties,
                doorCoordinator, clock);

        User user = new User();
        user.setId(1L);

        vehicle = new Vehicle();
        vehicle.setId(VEHICLE_ID);
        vehicle.setPlate("34 ABC 123");
        vehicle.setCity("İstanbul");
        vehicle.setLatitude(TAKSIM_LAT);
        vehicle.setLongitude(TAKSIM_LNG);
        vehicle.setTotalRangeKm(new BigDecimal("300.00"));
        vehicle.setRemainingRangeKm(new BigDecimal("280.00"));
        vehicle.setStatus(VehicleStatus.IN_USE);

        trip = new Trip();
        trip.setId(TRIP_ID);
        trip.setUser(user);
        trip.setVehicle(vehicle);
        trip.setStatus(TripStatus.ACTIVE);
        trip.setCity("İstanbul");
        trip.setPricePerKm(new BigDecimal("15.00"));
        trip.setStartLatitude(TAKSIM_LAT);
        trip.setStartLongitude(TAKSIM_LNG);
        trip.setDestinationLatitude(KADIKOY_LAT);
        trip.setDestinationLongitude(KADIKOY_LNG);
        trip.setEstimatedDistanceKm(new BigDecimal("6.13"));
        trip.setEstimatedDurationMin(10);
        trip.setEstimatedFee(new BigDecimal("91.95"));
        trip.setCreatedAt(NOW.minusSeconds(1200));
        trip.setStartedAt(NOW.minusSeconds(600)); // NOW − 10 dk → gerçekleşen süre 10 dk (tolerans içi)
    }

    // --- 1. Başarılı tamamlama (ara nokta Şişli + son konum Kadıköy) ---
    @Test
    @DisplayName("Başarılı tamamlama: COMPLETED, 10.87 km/10 dk, charge(trip, 91.95), fee CHARGED, araç AVAILABLE/Kadıköy/269.13, DURATION uyarısı YOK")
    void complete_success() {
        happyPath();

        TripSummaryResponse summary = service.completeTrip(TRIP_ID, request(KADIKOY_LAT, KADIKOY_LNG, NOW));

        assertThat(trip.getStatus()).isEqualTo(TripStatus.COMPLETED);
        assertThat(trip.getActualDistanceKm()).isEqualByComparingTo("10.87");
        assertThat(trip.getActualDurationMin()).isEqualTo(10);
        assertThat(trip.getCompletedAt()).isEqualTo(NOW);
        verify(walletService, times(1)).charge(eq(trip), eq(new BigDecimal("91.95")));
        assertThat(distanceFee.getStatus()).isEqualTo(FeeStatus.CHARGED);
        assertThat(distanceFee.getChargedAt()).isEqualTo(NOW);
        assertThat(vehicle.getStatus()).isEqualTo(VehicleStatus.AVAILABLE);
        assertThat(vehicle.getLatitude()).isEqualTo(KADIKOY_LAT);
        assertThat(vehicle.getLongitude()).isEqualTo(KADIKOY_LNG);
        assertThat(vehicle.getRemainingRangeKm()).isEqualByComparingTo("269.13");
        verify(tripWarningRepository, never()).save(any());
        assertThat(summary.status()).isEqualTo(TripStatus.COMPLETED);
        assertThat(summary.actualDistanceKm()).isEqualByComparingTo("10.87");
    }

    // --- 2. İdempotent: trip zaten COMPLETED → hiçbir yazma/charge/araç kilidi yok ---
    @Test
    @DisplayName("İdempotent: trip COMPLETED ise hiçbir yazma/charge/appendLocation/araç kilidi olmadan özet döner")
    void complete_alreadyCompleted_idempotent() {
        trip.setStatus(TripStatus.COMPLETED);
        when(tripRepository.findByIdForUpdate(TRIP_ID)).thenReturn(Optional.of(trip));

        TripSummaryResponse summary = service.completeTrip(TRIP_ID, request(KADIKOY_LAT, KADIKOY_LNG, NOW));

        assertThat(summary.status()).isEqualTo(TripStatus.COMPLETED);
        verify(walletService, never()).charge(any(), any());
        verify(tripLocationWriter, never()).appendLocation(any(), anyDouble(), anyDouble(), any(), any());
        verify(vehicleRepository, never()).findByIdForUpdate(any());
        verify(tripRepository, never()).saveAndFlush(any());
        verify(tripFeeRepository, never()).save(any());
        verify(tripWarningRepository, never()).save(any());
    }

    // --- 3. Mükerrer tahsilat engeli: iki ardışık complete → charge toplam 1 kez ---
    @Test
    @DisplayName("İki ardışık complete: ikincisi COMPLETED görür; charge toplam 1, appendLocation 1, araç kilidi 1")
    void complete_twice_chargeOnlyOnce() {
        happyPath();

        service.completeTrip(TRIP_ID, request(KADIKOY_LAT, KADIKOY_LNG, NOW));
        TripSummaryResponse second = service.completeTrip(TRIP_ID, request(KADIKOY_LAT, KADIKOY_LNG, NOW));

        assertThat(second.status()).isEqualTo(TripStatus.COMPLETED);
        verify(walletService, times(1)).charge(eq(trip), eq(new BigDecimal("91.95")));
        verify(tripLocationWriter, times(1)).appendLocation(any(), anyDouble(), anyDouble(), any(), any());
        verify(vehicleRepository, times(1)).findByIdForUpdate(VEHICLE_ID);
    }

    // --- 4. Tahsilat başarısız: PAYMENT_FAILED yayılır, her şey eski halinde kalır ---
    @Test
    @DisplayName("charge 402 PAYMENT_FAILED fırlatır → aynen yayılır; trip ACTIVE, completedAt null, araç IN_USE/280.00/konum aynı, DURATION uyarısı yok")
    void complete_paymentFailed_rollsBackState() {
        happyPath();
        doThrow(new ApiException(HttpStatus.PAYMENT_REQUIRED, "PAYMENT_FAILED", "Yetersiz bakiye."))
                .when(walletService).charge(any(), any());

        assertThatThrownBy(() -> service.completeTrip(TRIP_ID, request(KADIKOY_LAT, KADIKOY_LNG, NOW)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
                    assertThat(apiEx.getCode()).isEqualTo("PAYMENT_FAILED");
                });

        assertThat(trip.getStatus()).isEqualTo(TripStatus.ACTIVE);
        assertThat(trip.getCompletedAt()).isNull();
        assertThat(vehicle.getStatus()).isEqualTo(VehicleStatus.IN_USE);
        assertThat(vehicle.getRemainingRangeKm()).isEqualByComparingTo("280.00");
        assertThat(vehicle.getLatitude()).isEqualTo(TAKSIM_LAT);
        assertThat(vehicle.getLongitude()).isEqualTo(TAKSIM_LNG);
        verify(tripWarningRepository, never()).save(any());
    }

    // --- 5. Arızalı araç: MAINTENANCE KALIR, konum ve menzil yine güncellenir ---
    @Test
    @DisplayName("Araç MAINTENANCE (arıza var) → MAINTENANCE KALIR; konum Kadıköy, menzil 269.13")
    void complete_maintenanceVehicle_staysMaintenance() {
        lockTripActive();
        lockVehicle(VehicleStatus.MAINTENANCE);
        echoAppend();
        hasFault();
        path(SISLI_LAT, SISLI_LNG, KADIKOY_LAT, KADIKOY_LNG);
        existingFee("91.95");

        service.completeTrip(TRIP_ID, request(KADIKOY_LAT, KADIKOY_LNG, NOW));

        assertThat(vehicle.getStatus()).isEqualTo(VehicleStatus.MAINTENANCE);
        assertThat(vehicle.getLatitude()).isEqualTo(KADIKOY_LAT);
        assertThat(vehicle.getLongitude()).isEqualTo(KADIKOY_LNG);
        assertThat(vehicle.getRemainingRangeKm()).isEqualByComparingTo("269.13");
        assertThat(trip.getStatus()).isEqualTo(TripStatus.COMPLETED);
    }

    // --- 6. Varış dışı + arıza yok → 422 NOT_AT_ENDPOINT, charge yok ---
    @Test
    @DisplayName("Varış dışı (41.0050, 29.0270) ve arıza yok → 422 NOT_AT_ENDPOINT; charge çağrılmaz, araç değişmez")
    void complete_notAtEndpoint_rejected() {
        lockTripActive();
        lockVehicle(VehicleStatus.IN_USE);
        echoAppend();
        noFault();

        assertThatThrownBy(() -> service.completeTrip(TRIP_ID, request(ARRIVAL_OUT_LAT, ARRIVAL_OUT_LNG, NOW)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(apiEx.getCode()).isEqualTo("NOT_AT_ENDPOINT");
                });

        verify(walletService, never()).charge(any(), any());
        assertThat(vehicle.getStatus()).isEqualTo(VehicleStatus.IN_USE);
    }

    // --- 7. Varış içi (Kadıköy'e 0.345 km) ve başlangıç noktasında bitirme kabul ---
    @Test
    @DisplayName("Varış içi (40.9950, 29.0270) kabul; başlangıç noktasında (Taksim) bitirme kabul")
    void complete_withinArrivalRadiusOrAtStart_accepted() {
        happyPath();

        service.completeTrip(TRIP_ID, request(ARRIVAL_IN_LAT, ARRIVAL_IN_LNG, NOW));
        assertThat(trip.getStatus()).isEqualTo(TripStatus.COMPLETED);

        resetActive();
        service.completeTrip(TRIP_ID, request(TAKSIM_LAT, TAKSIM_LNG, NOW));
        assertThat(trip.getStatus()).isEqualTo(TripStatus.COMPLETED);

        verify(walletService, times(2)).charge(eq(trip), any());
    }

    // --- 8. Arıza varken varış dışı (Pendik) kabul ---
    @Test
    @DisplayName("Arıza varsa varış kuralı atlanır: Pendik'ten (varış dışı) tamamlama kabul, charge 1 kez")
    void complete_withFault_farFromEndpoint_accepted() {
        lockTripActive();
        lockVehicle(VehicleStatus.IN_USE);
        echoAppend();
        hasFault();
        path(PENDIK_LAT, PENDIK_LNG);
        existingFee("91.95");

        service.completeTrip(TRIP_ID, request(PENDIK_LAT, PENDIK_LNG, NOW));

        assertThat(trip.getStatus()).isEqualTo(TripStatus.COMPLETED);
        assertThat(vehicle.getStatus()).isEqualTo(VehicleStatus.AVAILABLE);
        verify(walletService, times(1)).charge(eq(trip), eq(new BigDecimal("91.95")));
    }

    // --- 9. Süre uyarısı: eşitlikte YOK, aşınca DURATION_EXCEEDED (10/13/20 mesajda) ---
    @Test
    @DisplayName("Süre: NOW-12dk (eşit) uyarı YOK; NOW-13dk → DURATION_EXCEEDED (mesajda 10, 13, 20)")
    void complete_durationWarning_boundary() {
        happyPath();

        trip.setStartedAt(NOW.minusSeconds(720)); // 12 dk → sınır (uyarı YOK)
        service.completeTrip(TRIP_ID, request(KADIKOY_LAT, KADIKOY_LNG, NOW));
        assertThat(trip.getActualDurationMin()).isEqualTo(12);
        verify(tripWarningRepository, never()).save(any());

        resetActive();
        trip.setStartedAt(NOW.minusSeconds(780)); // 13 dk → aşım
        service.completeTrip(TRIP_ID, request(KADIKOY_LAT, KADIKOY_LNG, NOW));
        assertThat(trip.getActualDurationMin()).isEqualTo(13);

        ArgumentCaptor<TripWarning> captor = ArgumentCaptor.forClass(TripWarning.class);
        verify(tripWarningRepository, times(1)).save(captor.capture());
        TripWarning warning = captor.getValue();
        assertThat(warning.getWarningType()).isEqualTo(WarningType.DURATION_EXCEEDED);
        assertThat(warning.getTrip()).isEqualTo(trip);
        assertThat(warning.getMessage()).contains("10").contains("13").contains("20");
    }

    // --- 10. Başlangıç = hedef, ücret 0.00: tamamlanır, charge(trip, 0.00), fee CHARGED ---
    @Test
    @DisplayName("Başlangıç=hedef (Taksim), tahmini ücret 0.00 → actualDistance 0.00, charge(trip, 0.00), fee CHARGED, araç AVAILABLE")
    void complete_zeroFee_completion() {
        trip.setDestinationLatitude(TAKSIM_LAT);
        trip.setDestinationLongitude(TAKSIM_LNG);
        trip.setEstimatedFee(new BigDecimal("0.00"));
        lockTripActive();
        lockVehicle(VehicleStatus.IN_USE);
        echoAppend();
        noFault();
        path(TAKSIM_LAT, TAKSIM_LNG);
        when(tripFeeRepository.findByTripIdAndFeeType(TRIP_ID, FeeType.DISTANCE)).thenReturn(Optional.empty());
        when(tripFeeRepository.save(any(TripFee.class))).thenAnswer(inv -> inv.getArgument(0));
        when(damageAssessmentService.assess(trip)).thenReturn(new BigDecimal("0.00"));

        service.completeTrip(TRIP_ID, request(TAKSIM_LAT, TAKSIM_LNG, NOW));

        assertThat(trip.getStatus()).isEqualTo(TripStatus.COMPLETED);
        assertThat(trip.getActualDistanceKm()).isEqualByComparingTo("0.00");
        verify(walletService, times(1)).charge(eq(trip), argZero());
        ArgumentCaptor<TripFee> captor = ArgumentCaptor.forClass(TripFee.class);
        verify(tripFeeRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(FeeStatus.CHARGED);
        assertThat(captor.getValue().getChargedAt()).isEqualTo(NOW);
        assertThat(vehicle.getStatus()).isEqualTo(VehicleStatus.AVAILABLE);
    }

    // --- 11. Hatalı son konum (lat 95): appendLocation INVALID_LOCATION → yayılır, charge yok, araç değişmez ---
    @Test
    @DisplayName("Son konum geçersiz (lat 95): appendLocation INVALID_LOCATION fırlatır → yayılır; charge never, araç IN_USE")
    void complete_invalidLocation_propagates() {
        lockTripActive();
        lockVehicle(VehicleStatus.IN_USE);
        when(tripLocationWriter.appendLocation(any(), anyDouble(), anyDouble(), any(), any()))
                .thenThrow(new ApiException(HttpStatus.BAD_REQUEST, "INVALID_LOCATION",
                        "Enlem -90 ile 90 arasında olmalıdır: 95.0."));

        assertThatThrownBy(() -> service.completeTrip(TRIP_ID, request(95.0, TAKSIM_LNG, NOW)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(apiEx.getCode()).isEqualTo("INVALID_LOCATION");
                });

        verify(walletService, never()).charge(any(), any());
        assertThat(vehicle.getStatus()).isEqualTo(VehicleStatus.IN_USE);
        assertThat(vehicle.getRemainingRangeKm()).isEqualByComparingTo("280.00");
    }

    // --- 12. Trip yok → 404; trip CREATED → 409 INVALID_TRIP_STATE, araç kilidi never ---
    @Test
    @DisplayName("Trip yok → 404 TRIP_NOT_FOUND; trip CREATED → 409 INVALID_TRIP_STATE (araç kilidi alınmaz)")
    void complete_notFound_andInvalidState() {
        when(tripRepository.findByIdForUpdate(TRIP_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.completeTrip(TRIP_ID, request(KADIKOY_LAT, KADIKOY_LNG, NOW)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(apiEx.getCode()).isEqualTo("TRIP_NOT_FOUND");
                });
        verify(vehicleRepository, never()).findByIdForUpdate(any());

        trip.setStatus(TripStatus.CREATED);
        when(tripRepository.findByIdForUpdate(TRIP_ID)).thenReturn(Optional.of(trip));
        assertThatThrownBy(() -> service.completeTrip(TRIP_ID, request(KADIKOY_LAT, KADIKOY_LNG, NOW)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(apiEx.getCode()).isEqualTo("INVALID_TRIP_STATE");
                });
        verify(vehicleRepository, never()).findByIdForUpdate(any());
    }

    // --- 13. Kilit sırası (InOrder): trip kilidi → araç kilidi → appendLocation → charge ---
    @Test
    @DisplayName("InOrder: tripRepository.findByIdForUpdate → vehicleRepository.findByIdForUpdate → appendLocation → walletService.charge")
    void complete_lockOrder_tripVehicleAppendCharge() {
        happyPath();

        service.completeTrip(TRIP_ID, request(KADIKOY_LAT, KADIKOY_LNG, NOW));

        InOrder inOrder = inOrder(tripRepository, vehicleRepository, tripLocationWriter, walletService);
        inOrder.verify(tripRepository).findByIdForUpdate(TRIP_ID);
        inOrder.verify(vehicleRepository).findByIdForUpdate(VEHICLE_ID);
        inOrder.verify(tripLocationWriter).appendLocation(eq(trip), anyDouble(), anyDouble(), any(), any());
        inOrder.verify(walletService).charge(eq(trip), any());
    }

    // --- 14. completeTrip @Transactional (Adım 11'in tersi: burada VAR) ---
    @Test
    @DisplayName("completeTrip metodu @Transactional taşıyor (tek transaction, rollback için şart)")
    void completeTrip_isTransactional() throws NoSuchMethodException {
        Method method = TripCompletionService.class.getMethod("completeTrip", Long.class, CompleteTripRequest.class);
        assertThat(method.getAnnotation(Transactional.class)).isNotNull();
    }

    /* ───── yardımcılar ───── */

    /** Mutlu yol: ACTIVE trip + IN_USE araç + appendLocation eko + arıza yok + [Şişli, Kadıköy] + ücret 91.95. */
    private void happyPath() {
        lockTripActive();
        lockVehicle(VehicleStatus.IN_USE);
        echoAppend();
        noFault();
        path(SISLI_LAT, SISLI_LNG, KADIKOY_LAT, KADIKOY_LNG);
        existingFee("91.95");
    }

    private void lockTripActive() {
        when(tripRepository.findByIdForUpdate(TRIP_ID)).thenReturn(Optional.of(trip));
    }

    private void lockVehicle(VehicleStatus status) {
        vehicle.setStatus(status);
        when(vehicleRepository.findByIdForUpdate(VEHICLE_ID)).thenReturn(Optional.of(vehicle));
    }

    /** appendLocation'ı, istenen koordinatları olduğu gibi 'yazılmış' gibi eko yapan sahte uygulama. */
    private void echoAppend() {
        when(tripLocationWriter.appendLocation(any(), anyDouble(), anyDouble(), any(), any()))
                .thenAnswer(inv -> new TripLocationWriter.LocationRecordResult(
                        location(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(4)),
                        false));
    }

    private void noFault() {
        when(faultReportRepository.existsByTripId(TRIP_ID)).thenReturn(false);
    }

    private void hasFault() {
        when(faultReportRepository.existsByTripId(TRIP_ID)).thenReturn(true);
    }

    /** Trip başlangıcından sonraki yol noktaları (çiftler: lat, lng). */
    private void path(double... latLng) {
        List<TripLocation> locations = new java.util.ArrayList<>();
        for (int i = 0; i < latLng.length; i += 2) {
            locations.add(location(latLng[i], latLng[i + 1]));
        }
        when(tripLocationRepository.findByTripIdOrderByRecordedAtAsc(TRIP_ID)).thenReturn(locations);
    }

    private void existingFee(String amount) {
        distanceFee = fee(amount);
        when(tripFeeRepository.findByTripIdAndFeeType(TRIP_ID, FeeType.DISTANCE)).thenReturn(Optional.of(distanceFee));
        // Adım 17 varsayılanı: hasar yok (0.00) → akış eskisiyle birebir aynı. Hasar testleri
        // bu stub'ı kendi tutarıyla ezer.
        when(damageAssessmentService.assess(trip)).thenReturn(new BigDecimal("0.00"));
    }

    /** İki ardışık çağrı arasında fixture'ı tekrar ACTIVE/IN_USE/280.00 durumuna döndürür. */
    private void resetActive() {
        trip.setStatus(TripStatus.ACTIVE);
        trip.setCompletedAt(null);
        trip.setActualDistanceKm(null);
        trip.setActualDurationMin(null);
        vehicle.setStatus(VehicleStatus.IN_USE);
        vehicle.setLatitude(TAKSIM_LAT);
        vehicle.setLongitude(TAKSIM_LNG);
        vehicle.setRemainingRangeKm(new BigDecimal("280.00"));
    }

    private BigDecimal argZero() {
        return org.mockito.ArgumentMatchers.argThat(amount -> amount.compareTo(BigDecimal.ZERO) == 0);
    }

    private TripFee fee(String amount) {
        TripFee fee = new TripFee();
        fee.setTrip(trip);
        fee.setFeeType(FeeType.DISTANCE);
        fee.setAmount(new BigDecimal(amount));
        fee.setStatus(FeeStatus.PENDING);
        fee.setCreatedAt(NOW);
        return fee;
    }

    private TripLocation location(Trip tripRef, double lat, double lng, Instant recordedAt) {
        TripLocation location = new TripLocation();
        location.setTrip(tripRef);
        location.setLatitude(lat);
        location.setLongitude(lng);
        location.setRecordedAt(recordedAt);
        return location;
    }

    private TripLocation location(double lat, double lng) {
        return location(trip, lat, lng, NOW);
    }

    private CompleteTripRequest request(double lat, double lng, Instant recordedAt) {
        return new CompleteTripRequest(lat, lng, null, recordedAt);
    }

    // --- Adım 17 / A8: kapı kilitleme (rollback DB düzeyinde; burada sıra + hata + idempotency) ---

    @Test
    @DisplayName("complete kapı hatası: 503 DOOR_LOCK_FAILED; kapı çağrısı charge ve saveAndFlush'tan SONRA (InOrder)")
    void complete_doorLockFails_throws503_afterChargeAndCompletedWrite() {
        happyPath();
        doThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DOOR_LOCK_FAILED",
                "Araç kapısı kilitlenemedi, seyahat kapatılamadı. Lütfen tekrar deneyebilirsiniz."))
                .when(doorCoordinator).lockForTripCompletion(trip, vehicle);

        assertThatThrownBy(() -> service.completeTrip(TRIP_ID, request(KADIKOY_LAT, KADIKOY_LNG, NOW)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(apiEx.getCode()).isEqualTo("DOOR_LOCK_FAILED");
                });

        InOrder inOrder = inOrder(walletService, tripRepository, doorCoordinator);
        inOrder.verify(walletService).charge(eq(trip), eq(new BigDecimal("91.95")));
        inOrder.verify(tripRepository).saveAndFlush(trip);
        inOrder.verify(doorCoordinator).lockForTripCompletion(trip, vehicle);
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("complete başarılı: kapı tam 1 kez; idempotent ikinci complete kapıyı YENİDEN çağırmaz")
    void complete_success_doorOnce_idempotentRepeatSkipsDoor() {
        happyPath();

        service.completeTrip(TRIP_ID, request(KADIKOY_LAT, KADIKOY_LNG, NOW));
        service.completeTrip(TRIP_ID, request(KADIKOY_LAT, KADIKOY_LNG, NOW));

        verify(doorCoordinator, times(1)).lockForTripCompletion(trip, vehicle);
    }

    // --- Adım 17 / B8: birleşik ücret (mesafe + hasar, TEK TRIP_CHARGE) ---

    @Test
    @DisplayName("hasar var: assess 500.00 → DAMAGE fee PENDING oluşur, charge(trip, 591.95, döküm); iki kalem de CHARGED")
    void complete_withDamage_singleCombinedCharge_bothFeesCharged() {
        happyPath();
        when(damageAssessmentService.assess(trip)).thenReturn(new BigDecimal("500.00"));
        // 1. soru (tamamlama akışı): yok → oluşturulur; 2. soru (buildSummary): yeni satırı döner.
        final TripFee[] savedDamageFee = new TripFee[1];
        when(tripFeeRepository.findByTripIdAndFeeType(TRIP_ID, FeeType.DAMAGE))
                .thenReturn(Optional.empty())
                .thenAnswer(inv -> Optional.ofNullable(savedDamageFee[0]));
        when(tripFeeRepository.save(any(TripFee.class))).thenAnswer(inv -> {
            savedDamageFee[0] = inv.getArgument(0);
            return savedDamageFee[0];
        });

        TripSummaryResponse summary = service.completeTrip(TRIP_ID, request(KADIKOY_LAT, KADIKOY_LNG, NOW));

        verify(walletService, times(1)).charge(eq(trip), eq(new BigDecimal("591.95")), anyString());
        verify(walletService, never()).charge(any(), any());
        ArgumentCaptor<TripFee> captor = ArgumentCaptor.forClass(TripFee.class);
        verify(tripFeeRepository).save(captor.capture());
        TripFee damageFee = captor.getValue();
        assertThat(damageFee.getFeeType()).isEqualTo(FeeType.DAMAGE);
        assertThat(damageFee.getAmount()).isEqualByComparingTo("500.00");
        assertThat(damageFee.getStatus()).isEqualTo(FeeStatus.CHARGED);
        assertThat(damageFee.getChargedAt()).isEqualTo(NOW);
        assertThat(distanceFee.getStatus()).isEqualTo(FeeStatus.CHARGED);
        assertThat(summary.damageFee()).isNotNull();
        assertThat(summary.damageFee().amount()).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("DAMAGE fee ZATEN varsa yeni satır oluşmaz (al-ya-da-oluştur), tek charge 591.95")
    void complete_withDamage_existingDamageFee_reused() {
        happyPath();
        TripFee existingDamageFee = new TripFee();
        existingDamageFee.setTrip(trip);
        existingDamageFee.setFeeType(FeeType.DAMAGE);
        existingDamageFee.setAmount(new BigDecimal("500.00"));
        existingDamageFee.setStatus(FeeStatus.PENDING);
        when(damageAssessmentService.assess(trip)).thenReturn(new BigDecimal("500.00"));
        when(tripFeeRepository.findByTripIdAndFeeType(TRIP_ID, FeeType.DAMAGE))
                .thenReturn(Optional.of(existingDamageFee));

        service.completeTrip(TRIP_ID, request(KADIKOY_LAT, KADIKOY_LNG, NOW));

        verify(tripFeeRepository, never()).save(any());
        verify(walletService).charge(eq(trip), eq(new BigDecimal("591.95")), anyString());
        assertThat(existingDamageFee.getStatus()).isEqualTo(FeeStatus.CHARGED);
    }

    @Test
    @DisplayName("hasarlı tahsilat 402: exception yayılır; trip ACTIVE, iki fee de PENDING kalır (DB'de rollback ile satırlar da kalkar)")
    void complete_damageChargeFails_stateUntouched() {
        happyPath();
        when(damageAssessmentService.assess(trip)).thenReturn(new BigDecimal("500.00"));
        when(tripFeeRepository.findByTripIdAndFeeType(TRIP_ID, FeeType.DAMAGE)).thenReturn(Optional.empty());
        TripFee damageFee = new TripFee();
        damageFee.setTrip(trip);
        damageFee.setFeeType(FeeType.DAMAGE);
        damageFee.setAmount(new BigDecimal("500.00"));
        damageFee.setStatus(FeeStatus.PENDING);
        when(tripFeeRepository.save(any(TripFee.class))).thenReturn(damageFee);
        doThrow(new ApiException(HttpStatus.PAYMENT_REQUIRED, "PAYMENT_FAILED", "Yetersiz bakiye."))
                .when(walletService).charge(any(), any(), anyString());

        assertThatThrownBy(() -> service.completeTrip(TRIP_ID, request(KADIKOY_LAT, KADIKOY_LNG, NOW)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("PAYMENT_FAILED"));

        assertThat(trip.getStatus()).isEqualTo(TripStatus.ACTIVE);
        assertThat(trip.getCompletedAt()).isNull();
        assertThat(distanceFee.getStatus()).isEqualTo(FeeStatus.PENDING);
        assertThat(damageFee.getStatus()).isEqualTo(FeeStatus.PENDING);
        verify(doorCoordinator, never()).lockForTripCompletion(any(), any());
    }

    @Test
    @DisplayName("idempotent tekrar: assess ve charge ikinci complete'te ÇAĞRILMAZ (toplam 1'er)")
    void complete_idempotent_assessAndChargeOnce() {
        happyPath();

        service.completeTrip(TRIP_ID, request(KADIKOY_LAT, KADIKOY_LNG, NOW));
        service.completeTrip(TRIP_ID, request(KADIKOY_LAT, KADIKOY_LNG, NOW));

        verify(damageAssessmentService, times(1)).assess(trip);
        verify(walletService, times(1)).charge(eq(trip), eq(new BigDecimal("91.95")));
    }
}

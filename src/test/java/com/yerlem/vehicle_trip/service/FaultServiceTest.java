package com.yerlem.vehicle_trip.service;

import com.yerlem.vehicle_trip.dto.FaultReportRequest;
import com.yerlem.vehicle_trip.dto.FaultReportResponse;
import com.yerlem.vehicle_trip.entity.FaultReport;
import com.yerlem.vehicle_trip.entity.Trip;
import com.yerlem.vehicle_trip.entity.Vehicle;
import com.yerlem.vehicle_trip.enums.FaultType;
import com.yerlem.vehicle_trip.enums.TripStatus;
import com.yerlem.vehicle_trip.enums.VehicleStatus;
import com.yerlem.vehicle_trip.exception.ApiException;
import com.yerlem.vehicle_trip.repository.FaultReportRepository;
import com.yerlem.vehicle_trip.repository.TripRepository;
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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Adım 12 — arıza bildirimi (D11, D12, D18).
 * IN_USE → MAINTENANCE geçişi GERÇEK VehicleService üzerinden yapılır (tek
 * nokta kuralı);
 * mock yalnızca veri kaynağıdır.
 */
@ExtendWith(MockitoExtension.class)
class FaultServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
    private static final Long TRIP_ID = 10L;
    private static final Long VEHICLE_ID = 2L;

    @Mock
    private TripRepository tripRepository;
    @Mock
    private VehicleRepository vehicleRepository;
    @Mock
    private FaultReportRepository faultReportRepository;
    @Mock
    private VehicleZoneRepository vehicleZoneRepository;

    private FaultService faultService;
    private Trip trip;
    private Vehicle vehicle;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        // Gerçek VehicleService: D11 geçişi (markMaintenanceDueToFault) gerçekten
        // çalışsın
        VehicleService vehicleService = new VehicleService(vehicleRepository, vehicleZoneRepository, tripRepository);
        faultService = new FaultService(tripRepository, vehicleRepository, faultReportRepository,
                vehicleService, clock);

        vehicle = new Vehicle();
        vehicle.setId(VEHICLE_ID);
        vehicle.setPlate("34 ABC 123");
        vehicle.setCity("İstanbul");
        vehicle.setStatus(VehicleStatus.IN_USE);

        trip = new Trip();
        trip.setId(TRIP_ID);
        trip.setVehicle(vehicle);
        trip.setStatus(TripStatus.ACTIVE);
    }

    // --- 1. Lastik arızası: rapor yazılır, araç MAINTENANCE olur ---
    @Test
    @DisplayName("Lastik arızası: fault_report yazılır, araç IN_USE → MAINTENANCE olur, yanıt kayıt + araç durumu döner")
    void tireBlowout_reportWritten_vehicleBecomesMaintenance() {
        stubTripLockable();
        stubVehicleLockable();
        stubSaveAssignsId();

        FaultReportResponse response = faultService.reportFault(TRIP_ID,
                new FaultReportRequest("TIRE_BLOWOUT", "Ön sağ lastik patladı."));

        assertThat(vehicle.getStatus()).isEqualTo(VehicleStatus.MAINTENANCE);
        assertThat(response.id()).isEqualTo(500L);
        assertThat(response.faultType()).isEqualTo(FaultType.TIRE_BLOWOUT);
        assertThat(response.description()).isEqualTo("Ön sağ lastik patladı.");
        assertThat(response.reportedAt()).isEqualTo(NOW);
        assertThat(response.tripId()).isEqualTo(TRIP_ID);
        assertThat(response.vehicleId()).isEqualTo(VEHICLE_ID);
        assertThat(response.vehicleStatus()).isEqualTo(VehicleStatus.MAINTENANCE);

        ArgumentCaptor<FaultReport> captor = ArgumentCaptor.forClass(FaultReport.class);
        verify(faultReportRepository).save(captor.capture());
        assertThat(captor.getValue().getFaultType()).isEqualTo(FaultType.TIRE_BLOWOUT);
        assertThat(captor.getValue().getReportedAt()).isEqualTo(NOW);
    }

    // --- 2. İkinci arıza: hata yok, araç MAINTENANCE kalır, ikinci rapor yazılır
    // ---
    @Test
    @DisplayName("İkinci arıza: hata yok; araç MAINTENANCE kalır (no-op), ikinci fault_report yine yazılır")
    void secondFault_noError_vehicleStaysMaintenance_secondReportWritten() {
        stubTripLockable();
        stubVehicleLockable();
        stubSaveAssignsId();

        faultService.reportFault(TRIP_ID, new FaultReportRequest("TIRE_BLOWOUT", null));
        faultService.reportFault(TRIP_ID, new FaultReportRequest("BATTERY", "Akü bitti."));

        assertThat(vehicle.getStatus()).isEqualTo(VehicleStatus.MAINTENANCE);
        verify(faultReportRepository, times(2)).save(any(FaultReport.class));
        verify(vehicleRepository, times(1)).save(any(Vehicle.class)); // yalnızca ilk arızada durum güncellendi
    }

    // --- 3. Trip ACTIVE değil → 409 (araç kilidi alınmaz) ---
    @Test
    @DisplayName("Trip ACTIVE değilse 409 INVALID_TRIP_STATE; araç kilidi hiç alınmaz, hiçbir şey yazılmaz")
    void tripNotActive_rejected() {
        trip.setStatus(TripStatus.COMPLETED);
        stubTripLockable();

        assertRejected(HttpStatus.CONFLICT, "INVALID_TRIP_STATE",
                () -> faultService.reportFault(TRIP_ID, new FaultReportRequest("TIRE_BLOWOUT", null)));
        verify(vehicleRepository, never()).findByIdForUpdate(any());
    }

    // --- 4. Trip yok → 404 ---
    @Test
    @DisplayName("Trip bulunamadı → 404 TRIP_NOT_FOUND, hiçbir şey yazılmaz")
    void tripNotFound_rejected() {
        when(tripRepository.findByIdForUpdate(TRIP_ID)).thenReturn(Optional.empty());

        assertRejected(HttpStatus.NOT_FOUND, "TRIP_NOT_FOUND",
                () -> faultService.reportFault(TRIP_ID, new FaultReportRequest("TIRE_BLOWOUT", null)));
    }

    // --- 5. Kilit sırası (D18): önce trip, sonra araç; araç save'i rapor
    // save'inden önce ---
    @Test
    @DisplayName("Kilit sırası (D18): ÖNCE trip kilidi, SONRA araç kilidi; araç save'i rapor save'inden önce")
    void lockOrder_tripThenVehicle() {
        stubTripLockable();
        stubVehicleLockable();
        stubSaveAssignsId();

        faultService.reportFault(TRIP_ID, new FaultReportRequest("ENGINE", null));

        InOrder inOrder = inOrder(tripRepository, vehicleRepository, faultReportRepository);
        inOrder.verify(tripRepository).findByIdForUpdate(TRIP_ID);
        inOrder.verify(vehicleRepository).findByIdForUpdate(VEHICLE_ID);
        inOrder.verify(vehicleRepository).save(any(Vehicle.class));
        inOrder.verify(faultReportRepository).save(any(FaultReport.class));
    }

    // --- 6. Geçersiz faultType → 400 (fail-fast, kilitler alınmaz) ---
    @Test
    @DisplayName("Geçersiz faultType → 400 INVALID_FAULT_TYPE; kilitler alınmaz, hiçbir şey yazılmaz")
    void invalidFaultType_rejected() {
        assertRejected(HttpStatus.BAD_REQUEST, "INVALID_FAULT_TYPE",
                () -> faultService.reportFault(TRIP_ID, new FaultReportRequest("BLA", null)));
        verify(tripRepository, never()).findByIdForUpdate(any());
        verify(vehicleRepository, never()).findByIdForUpdate(any());
    }

    // --- 7. Küçük harfli/boşluklu faultType kabul edilir (normalizasyon) ---
    @Test
    @DisplayName("Küçük harfli/boşluklu faultType: \"  engine \" → ENGINE kabul edilir")
    void lowercaseAndSpaces_faultType_accepted() {
        stubTripLockable();
        stubVehicleLockable();
        stubSaveAssignsId();

        FaultReportResponse response = faultService.reportFault(TRIP_ID,
                new FaultReportRequest("  engine ", null));

        assertThat(response.faultType()).isEqualTo(FaultType.ENGINE);
        verify(faultReportRepository).save(any(FaultReport.class));
    }

    // --- 8. Varsayılan locale tr-TR iken "tire_blowout" kabul edilir (Locale.ROOT)
    // ---
    @Test
    @DisplayName("Varsayılan locale tr-TR iken \"tire_blowout\" → TIRE_BLOWOUT (Locale.ROOT: Türkçe i→İ hatası yok)")
    void defaultLocaleTurkish_tireBlowout_accepted() {
        stubTripLockable();
        stubVehicleLockable();
        stubSaveAssignsId();
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            FaultReportResponse response = faultService.reportFault(TRIP_ID,
                    new FaultReportRequest("tire_blowout", null));
            assertThat(response.faultType()).isEqualTo(FaultType.TIRE_BLOWOUT);
        } finally {
            Locale.setDefault(original);
        }
        verify(faultReportRepository).save(any(FaultReport.class));
    }

    // --- 9. Tutarsız durum: araç AVAILABLE iken arıza → yine MAINTENANCE ---
    @Test
    @DisplayName("Tutarsız durum: araç AVAILABLE iken arıza bildirilirse yine MAINTENANCE yapılır")
    void availableVehicle_inconsistentState_stillMaintenance() {
        vehicle.setStatus(VehicleStatus.AVAILABLE);
        stubTripLockable();
        stubVehicleLockable();
        stubSaveAssignsId();

        faultService.reportFault(TRIP_ID, new FaultReportRequest("DOOR", null));

        assertThat(vehicle.getStatus()).isEqualTo(VehicleStatus.MAINTENANCE);
        verify(faultReportRepository).save(any(FaultReport.class));
    }

    // --- 10. reportedAt Clock'tan; trip/vehicle ilişkileri doğru (captor) ---
    @Test
    @DisplayName("Raporda reportedAt Clock'tan gelir; tripId/vehicleId doğru eşlenir (captor)")
    void reportedAt_fromClock_tripIdVehicleId_correct() {
        stubTripLockable();
        stubVehicleLockable();
        stubSaveAssignsId();

        faultService.reportFault(TRIP_ID, new FaultReportRequest("BATTERY", "Akü bitti."));

        ArgumentCaptor<FaultReport> captor = ArgumentCaptor.forClass(FaultReport.class);
        verify(faultReportRepository).save(captor.capture());
        FaultReport report = captor.getValue();
        assertThat(report.getReportedAt()).isEqualTo(NOW);
        assertThat(report.getTrip().getId()).isEqualTo(TRIP_ID);
        assertThat(report.getVehicle().getId()).isEqualTo(VEHICLE_ID);
        assertThat(report.getDescription()).isEqualTo("Akü bitti.");
    }

    // --- 11. GET faults: sıralı liste doğru eşlenir ---
    @Test
    @DisplayName("GET faults: reportedAt sıralı liste doğru eşlenir (id, tür, araç durumu)")
    void getFaults_success() {
        vehicle.setStatus(VehicleStatus.MAINTENANCE);
        when(tripRepository.existsById(TRIP_ID)).thenReturn(true);
        FaultReport first = report(501L, FaultType.TIRE_BLOWOUT, NOW.minusSeconds(120));
        FaultReport second = report(502L, FaultType.BATTERY, NOW.minusSeconds(60));
        when(faultReportRepository.findByTripIdOrderByReportedAtAsc(TRIP_ID))
                .thenReturn(List.of(first, second));

        List<FaultReportResponse> faults = faultService.getFaults(TRIP_ID);

        assertThat(faults).hasSize(2);
        assertThat(faults.get(0).id()).isEqualTo(501L);
        assertThat(faults.get(0).faultType()).isEqualTo(FaultType.TIRE_BLOWOUT);
        assertThat(faults.get(0).vehicleStatus()).isEqualTo(VehicleStatus.MAINTENANCE);
        assertThat(faults.get(1).id()).isEqualTo(502L);
        assertThat(faults.get(1).reportedAt()).isEqualTo(NOW.minusSeconds(60));
    }

    // --- 12. GET faults: olmayan trip → 404 ---
    @Test
    @DisplayName("GET faults: olmayan trip → 404 TRIP_NOT_FOUND")
    void getFaults_tripNotFound() {
        when(tripRepository.existsById(TRIP_ID)).thenReturn(false);

        assertRejected(HttpStatus.NOT_FOUND, "TRIP_NOT_FOUND", () -> faultService.getFaults(TRIP_ID));
    }

    // --- 13. faultType null → 400 (NPE yok), kilitler alınmaz ---
    @Test
    @DisplayName("faultType null → 400 INVALID_FAULT_TYPE (NPE yok, geçerli türler mesajda); kilitler alınmaz")
    void nullFaultType_rejected() {
        assertThatThrownBy(() -> faultService.reportFault(TRIP_ID, new FaultReportRequest(null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(apiEx.getCode()).isEqualTo("INVALID_FAULT_TYPE");
                    assertThat(apiEx.getMessage()).contains("TIRE_BLOWOUT").contains("OTHER");
                });
        verify(tripRepository, never()).findByIdForUpdate(any());
        verify(vehicleRepository, never()).findByIdForUpdate(any());
        verify(faultReportRepository, never()).save(any());
        verify(vehicleRepository, never()).save(any());
    }

    // --- 14. faultType boş string → 400 (NPE yok), kilitler alınmaz ---
    @Test
    @DisplayName("faultType boş string → 400 INVALID_FAULT_TYPE (geçerli türler mesajda); kilitler alınmaz")
    void blankFaultType_rejected() {
        assertThatThrownBy(() -> faultService.reportFault(TRIP_ID, new FaultReportRequest("", null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(apiEx.getCode()).isEqualTo("INVALID_FAULT_TYPE");
                    assertThat(apiEx.getMessage()).contains("TIRE_BLOWOUT").contains("OTHER");
                });
        verify(tripRepository, never()).findByIdForUpdate(any());
        verify(vehicleRepository, never()).findByIdForUpdate(any());
        verify(faultReportRepository, never()).save(any());
        verify(vehicleRepository, never()).save(any());
    }

    // --- yardımcılar ---

    /**
     * Red durumlarını doğrular: beklenen HttpStatus + kod VE hiçbir şey yazılmadığı
     * (never save).
     */
    private void assertRejected(HttpStatus status, String code, Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(status);
                    assertThat(apiEx.getCode()).isEqualTo(code);
                });
        verify(faultReportRepository, never()).save(any());
        verify(vehicleRepository, never()).save(any());
    }

    private void stubTripLockable() {
        when(tripRepository.findByIdForUpdate(TRIP_ID)).thenReturn(Optional.of(trip));
    }

    private void stubVehicleLockable() {
        when(vehicleRepository.findByIdForUpdate(VEHICLE_ID)).thenReturn(Optional.of(vehicle));
    }

    private void stubSaveAssignsId() {
        when(faultReportRepository.save(any(FaultReport.class))).thenAnswer(inv -> {
            FaultReport report = inv.getArgument(0);
            report.setId(500L);
            return report;
        });
    }

    private FaultReport report(Long id, FaultType faultType, Instant reportedAt) {
        FaultReport report = new FaultReport();
        report.setId(id);
        report.setTrip(trip);
        report.setVehicle(vehicle);
        report.setFaultType(faultType);
        report.setReportedAt(reportedAt);
        return report;
    }
}

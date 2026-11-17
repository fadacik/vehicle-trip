package com.yerlem.vehicle_trip.service;

import com.yerlem.vehicle_trip.config.SpeedLimitProperties;
import com.yerlem.vehicle_trip.config.TripProperties;
import com.yerlem.vehicle_trip.dto.LocationRecordRequest;
import com.yerlem.vehicle_trip.dto.TripLocationResponse;
import com.yerlem.vehicle_trip.dto.TripWarningResponse;
import com.yerlem.vehicle_trip.entity.Trip;
import com.yerlem.vehicle_trip.entity.TripLocation;
import com.yerlem.vehicle_trip.entity.TripWarning;
import com.yerlem.vehicle_trip.entity.Vehicle;
import com.yerlem.vehicle_trip.entity.VehicleZone;
import com.yerlem.vehicle_trip.enums.TripStatus;
import com.yerlem.vehicle_trip.enums.VehicleStatus;
import com.yerlem.vehicle_trip.enums.WarningType;
import com.yerlem.vehicle_trip.exception.ApiException;
import com.yerlem.vehicle_trip.repository.TripLocationRepository;
import com.yerlem.vehicle_trip.repository.TripRepository;
import com.yerlem.vehicle_trip.repository.TripWarningRepository;
import com.yerlem.vehicle_trip.repository.VehicleZoneRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
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
import static org.mockito.Mockito.*;

/**
 * Adım 11 — konum kaydı ve hız uyarısı (D9, D10).
 * Coğrafi hesap GERÇEK ZoneService + GeoUtils üzerinden yapılır; mock yalnızca veri kaynağıdır.
 * Mesafeler bağımsız hesaplanmış literal değerlerdir (formül test içinde tekrar hesaplanmaz):
 *   Taksim  (41.0370, 28.9850) → merkez ~3.25 km  (şehir içi, limit 80; alan içi → city=İstanbul)
 *   Pendik  (40.8780, 29.2330) → merkez ~25.82 km (şehir dışı, limit 120; alan içi → city=İstanbul)
 *   Silivri (41.0742, 28.2464) → merkez ~61.83 km (alan dışı → city=null, limit 120)
 */
@ExtendWith(MockitoExtension.class)
class TripLocationServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
    private static final Instant STARTED_AT = NOW.minusSeconds(300);
    private static final Long TRIP_ID = 10L;
    private static final Long VEHICLE_ID = 2L;

    // İstanbul araç alanı: merkez (41.0082, 28.9784), yarıçap 60 km (seed V2 ile aynı)
    private static final double ZONE_CENTER_LAT = 41.0082;
    private static final double ZONE_CENTER_LNG = 28.9784;

    private static final double TAKSIM_LAT = 41.0370;
    private static final double TAKSIM_LNG = 28.9850;
    private static final double PENDIK_LAT = 40.8780;
    private static final double PENDIK_LNG = 29.2330;
    private static final double SILIVRI_LAT = 41.0742;
    private static final double SILIVRI_LNG = 28.2464;

    @Mock
    private TripRepository tripRepository;
    @Mock
    private TripLocationRepository tripLocationRepository;
    @Mock
    private TripWarningRepository tripWarningRepository;
    @Mock
    private VehicleZoneRepository vehicleZoneRepository;

    private TripLocationService tripLocationService;
    private TripLocationWriter tripLocationWriter;
    private Trip trip;
    private Vehicle vehicle;
    private VehicleZone zone;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        TripProperties tripProperties = new TripProperties(40, 20, 1.0, 15.0, 1.0, 60);
        SpeedLimitProperties speedLimitProperties = new SpeedLimitProperties(80, 120);
        // Gerçek ZoneService: haversine mesafesi gerçek hesaplansın (literal mesafelerle)
        ZoneService zoneService = new ZoneService(vehicleZoneRepository);
        // Gerçek TripLocationWriter: kilit sırası ve ihlal yayılımı davranışsal olarak doğrulanır
        tripLocationWriter = new TripLocationWriter(tripRepository, tripLocationRepository,
                tripWarningRepository, zoneService, tripProperties, speedLimitProperties, clock);
        tripLocationService = new TripLocationService(tripRepository, tripLocationRepository,
                tripWarningRepository, tripLocationWriter, clock);

        vehicle = new Vehicle();
        vehicle.setId(VEHICLE_ID);
        vehicle.setPlate("34 ABC 123");
        vehicle.setCity("İstanbul");
        vehicle.setLatitude(ZONE_CENTER_LAT);
        vehicle.setLongitude(ZONE_CENTER_LNG);
        vehicle.setTotalRangeKm(new BigDecimal("300.00"));
        vehicle.setRemainingRangeKm(new BigDecimal("300.00"));
        vehicle.setStatus(VehicleStatus.IN_USE);

        trip = new Trip();
        trip.setId(TRIP_ID);
        trip.setVehicle(vehicle);
        trip.setStatus(TripStatus.ACTIVE);
        trip.setStartedAt(STARTED_AT);

        zone = new VehicleZone();
        zone.setId(1L);
        zone.setName("İstanbul");
        zone.setCenterLatitude(ZONE_CENTER_LAT);
        zone.setCenterLongitude(ZONE_CENTER_LNG);
        zone.setRadiusKm(new BigDecimal("60.00"));
    }

    // --- 1. Taksim 85 → şehir içi limit 80 AŞILIR → uyarı ---
    @Test
    @DisplayName("Taksim (şehir içi) 85 km/sa → 80 limiti aşıldı: SPEED_LIMIT uyarısı yazılır, city=İstanbul")
    void taksim_85kmh_speedLimitWarning() {
        stubHappyPath(NOW);

        TripLocationResponse response = tripLocationService.recordLocation(TRIP_ID,
                request(TAKSIM_LAT, TAKSIM_LNG, "85", NOW));

        assertThat(response.duplicate()).isFalse();
        assertThat(response.id()).isEqualTo(100L);
        assertThat(response.city()).isEqualTo("İstanbul");
        assertThat(response.latitude()).isEqualTo(TAKSIM_LAT);
        assertThat(response.longitude()).isEqualTo(TAKSIM_LNG);
        assertThat(response.speedKmh()).isEqualByComparingTo("85");
        assertThat(response.recordedAt()).isEqualTo(NOW);

        ArgumentCaptor<TripLocation> locationCaptor = ArgumentCaptor.forClass(TripLocation.class);
        verify(tripLocationRepository).saveAndFlush(locationCaptor.capture());
        assertThat(locationCaptor.getValue().getCity()).isEqualTo("İstanbul");
        assertThat(locationCaptor.getValue().getRecordedAt()).isEqualTo(NOW);

        ArgumentCaptor<TripWarning> warningCaptor = ArgumentCaptor.forClass(TripWarning.class);
        verify(tripWarningRepository).save(warningCaptor.capture());
        TripWarning warning = warningCaptor.getValue();
        assertThat(warning.getWarningType()).isEqualTo(WarningType.SPEED_LIMIT);
        assertThat(warning.getTrip()).isEqualTo(trip);
        assertThat(warning.getMessage())
                .contains("85")
                .contains("80")
                .contains(NOW.toString());
        assertThat(warning.getCreatedAt()).isEqualTo(NOW);
    }

    // --- 2. Taksim 80 → eşit, aşım DEĞİL → uyarı yok ---
    @Test
    @DisplayName("Taksim 80 km/sa → limite eşit: uyarı YOK (yalnızca aşım uyarı üretir)")
    void taksim_80kmh_noWarning() {
        stubHappyPath(NOW);

        tripLocationService.recordLocation(TRIP_ID, request(TAKSIM_LAT, TAKSIM_LNG, "80", NOW));

        verify(tripWarningRepository, never()).save(any());
    }

    // --- 3. Pendik 85 → şehir dışı limit 120 → uyarı yok ---
    @Test
    @DisplayName("Pendik (şehir dışı, ~25.82 km) 85 km/sa → limit 120 aşılmadı: uyarı YOK, city=İstanbul")
    void pendik_85kmh_noWarning() {
        stubHappyPath(NOW);

        tripLocationService.recordLocation(TRIP_ID, request(PENDIK_LAT, PENDIK_LNG, "85", NOW));

        ArgumentCaptor<TripLocation> locationCaptor = ArgumentCaptor.forClass(TripLocation.class);
        verify(tripLocationRepository).saveAndFlush(locationCaptor.capture());
        assertThat(locationCaptor.getValue().getCity()).isEqualTo("İstanbul"); // alan içi (≤60 km)
        verify(tripWarningRepository, never()).save(any());
    }

    // --- 4. Pendik 125 → şehir dışı limit 120 AŞILIR → uyarı ---
    @Test
    @DisplayName("Pendik 125 km/sa → limit 120 aşıldı: SPEED_LIMIT uyarısı yazılır")
    void pendik_125kmh_warning() {
        stubHappyPath(NOW);

        tripLocationService.recordLocation(TRIP_ID, request(PENDIK_LAT, PENDIK_LNG, "125", NOW));

        ArgumentCaptor<TripWarning> warningCaptor = ArgumentCaptor.forClass(TripWarning.class);
        verify(tripWarningRepository).save(warningCaptor.capture());
        assertThat(warningCaptor.getValue().getWarningType()).isEqualTo(WarningType.SPEED_LIMIT);
        assertThat(warningCaptor.getValue().getMessage())
                .contains("125")
                .contains("120");
    }

    // --- 5. Silivri → alan dışı: city=null, limit 120, 125 AŞILIR → uyarı ---
    @Test
    @DisplayName("Silivri (alan dışı, ~61.83 km) 125 km/sa → city=null ve limit 120 aşıldı: uyarı yazılır")
    void silivri_125kmh_warning_cityNull() {
        stubHappyPath(NOW);

        TripLocationResponse response = tripLocationService.recordLocation(TRIP_ID,
                request(SILIVRI_LAT, SILIVRI_LNG, "125", NOW));

        assertThat(response.city()).isNull();
        ArgumentCaptor<TripLocation> locationCaptor = ArgumentCaptor.forClass(TripLocation.class);
        verify(tripLocationRepository).saveAndFlush(locationCaptor.capture());
        assertThat(locationCaptor.getValue().getCity()).isNull();

        ArgumentCaptor<TripWarning> warningCaptor = ArgumentCaptor.forClass(TripWarning.class);
        verify(tripWarningRepository).save(warningCaptor.capture());
        assertThat(warningCaptor.getValue().getMessage()).contains("120");
    }

    // --- 6. Başarılı kayıt ---
    @Test
    @DisplayName("Başarılı kayıt: duplicate=false, alanlar doğru, tek satır yazılır")
    void successfulRecord() {
        stubHappyPath(NOW);

        TripLocationResponse response = tripLocationService.recordLocation(TRIP_ID,
                request(TAKSIM_LAT, TAKSIM_LNG, "50", NOW));

        assertThat(response.duplicate()).isFalse();
        assertThat(response.city()).isEqualTo("İstanbul");
        verify(tripLocationRepository, times(1)).saveAndFlush(any(TripLocation.class));
        verify(tripWarningRepository, never()).save(any()); // 50 < 80
    }

    // --- 7. Aynı recordedAt tekrar → duplicate=true, hiçbir şey yazılmaz ---
    @Test
    @DisplayName("Aynı recordedAt tekrarı: yeni satır eklenmez, uyarı yazılmaz, mevcut kayıt duplicate=true döner")
    void duplicateRecordedAt_returnsExisting() {
        stubTripReadable();
        stubTripLockable();
        when(tripLocationRepository.findByTripIdAndRecordedAt(TRIP_ID, NOW))
                .thenReturn(Optional.of(existingLocation()));

        // İstekte farklı koordinat/hız olsa bile yanıtta MEVCUT satır döner
        TripLocationResponse response = tripLocationService.recordLocation(TRIP_ID,
                request(PENDIK_LAT, PENDIK_LNG, "90", NOW));

        assertThat(response.duplicate()).isTrue();
        assertThat(response.id()).isEqualTo(100L);
        assertThat(response.latitude()).isEqualTo(TAKSIM_LAT);
        assertThat(response.speedKmh()).isEqualByComparingTo("85");
        verify(tripLocationRepository, never()).saveAndFlush(any());
        verify(tripLocationRepository, never()).save(any());
        verify(tripWarningRepository, never()).save(any());
    }

    // --- 8. recordedAt başlangıçtan önce → 400 ---
    @Test
    @DisplayName("recordedAt seyahat başlangıcından önce → 400 INVALID_LOCATION, hiçbir şey yazılmaz")
    void recordedAtBeforeStart_rejected() {
        stubTripReadable();
        stubTripLockable();

        assertRejected(HttpStatus.BAD_REQUEST, "INVALID_LOCATION", () -> tripLocationService.recordLocation(TRIP_ID,
                request(TAKSIM_LAT, TAKSIM_LNG, "50", STARTED_AT.minusSeconds(1))));
    }

    // --- 9. recordedAt son kayıttan eski → 400 ---
    @Test
    @DisplayName("recordedAt son kayıttan eski → 400 INVALID_LOCATION, hiçbir şey yazılmaz")
    void recordedAtOlderThanLast_rejected() {
        stubTripReadable();
        stubTripLockable();
        Instant newTime = NOW.minusSeconds(61); // 09:58:59 → başlangıçtan sonra ama son kayıttan eski
        when(tripLocationRepository.findByTripIdAndRecordedAt(TRIP_ID, newTime)).thenReturn(Optional.empty());
        TripLocation last = existingLocation();
        last.setId(99L);
        last.setRecordedAt(NOW.minusSeconds(60)); // son kayıt 09:59
        when(tripLocationRepository.findFirstByTripIdOrderByRecordedAtDesc(TRIP_ID))
                .thenReturn(Optional.of(last));

        assertRejected(HttpStatus.BAD_REQUEST, "INVALID_LOCATION", () -> tripLocationService.recordLocation(TRIP_ID,
                request(TAKSIM_LAT, TAKSIM_LNG, "50", newTime)));
    }

    // --- 10. recordedAt gelecekte (>60 sn) → 400 ---
    @Test
    @DisplayName("recordedAt sunucu saatinden 61 sn ileride → 400 INVALID_LOCATION, hiçbir şey yazılmaz")
    void recordedAtTooFuture_rejected() {
        stubTripReadable();
        stubTripLockable();
        Instant future = NOW.plusSeconds(61);
        when(tripLocationRepository.findByTripIdAndRecordedAt(TRIP_ID, future)).thenReturn(Optional.empty());
        when(tripLocationRepository.findFirstByTripIdOrderByRecordedAtDesc(TRIP_ID)).thenReturn(Optional.empty());

        assertRejected(HttpStatus.BAD_REQUEST, "INVALID_LOCATION", () -> tripLocationService.recordLocation(TRIP_ID,
                request(TAKSIM_LAT, TAKSIM_LNG, "50", future)));
    }

    // --- 11a. Geçersiz enlem → 400 ---
    @Test
    @DisplayName("Enlem 90.5 → 400 INVALID_LOCATION, hiçbir şey yazılmaz")
    void invalidLatitude_rejected() {
        stubTripReadable();
        stubTripLockable();

        assertRejected(HttpStatus.BAD_REQUEST, "INVALID_LOCATION", () -> tripLocationService.recordLocation(TRIP_ID,
                request(90.5, TAKSIM_LNG, "50", NOW)));
    }

    // --- 11b. Geçersiz boylam → 400 ---
    @Test
    @DisplayName("Boylam -180.5 → 400 INVALID_LOCATION, hiçbir şey yazılmaz")
    void invalidLongitude_rejected() {
        stubTripReadable();
        stubTripLockable();

        assertRejected(HttpStatus.BAD_REQUEST, "INVALID_LOCATION", () -> tripLocationService.recordLocation(TRIP_ID,
                request(TAKSIM_LAT, -180.5, "50", NOW)));
    }

    // --- 11c. Negatif hız → 400 ---
    @Test
    @DisplayName("Hız -5 km/sa → 400 INVALID_LOCATION, hiçbir şey yazılmaz")
    void negativeSpeed_rejected() {
        stubTripReadable();
        stubTripLockable();

        assertRejected(HttpStatus.BAD_REQUEST, "INVALID_LOCATION", () -> tripLocationService.recordLocation(TRIP_ID,
                request(TAKSIM_LAT, TAKSIM_LNG, "-5", NOW)));
    }

    // --- 12a. Trip ACTIVE değil → 409 (dış katman hızlı yolu, kilide gitmeden) ---
    @Test
    @DisplayName("Trip CREATED durumunda → 409 INVALID_TRIP_STATE; araç kilidi hiç alınmaz, hiçbir şey yazılmaz")
    void tripNotActive_rejected() {
        trip.setStatus(TripStatus.CREATED);
        stubTripReadable();

        assertRejected(HttpStatus.CONFLICT, "INVALID_TRIP_STATE", () -> tripLocationService.recordLocation(TRIP_ID,
                request(TAKSIM_LAT, TAKSIM_LNG, "50", NOW)));
        verify(tripRepository, never()).findByIdForUpdate(any());
    }

    // --- 12b. Trip yok → 404 ---
    @Test
    @DisplayName("Trip bulunamadı → 404 TRIP_NOT_FOUND, hiçbir şey yazılmaz")
    void tripNotFound_rejected() {
        when(tripRepository.findById(TRIP_ID)).thenReturn(Optional.empty());

        assertRejected(HttpStatus.NOT_FOUND, "TRIP_NOT_FOUND", () -> tripLocationService.recordLocation(TRIP_ID,
                request(TAKSIM_LAT, TAKSIM_LNG, "50", NOW)));
    }

    // --- 13. Eşzamanlı tekrar: unique kısıt ihlali → duplicate yanıtı (son savunma) ---
    @Test
    @DisplayName("Unique kısıt ihlali (uq_trip_locations_trip_time) → 200 yerine duplicate=true; uyarı yazılmaz")
    void constraintViolation_convertedToDuplicate() {
        stubTripReadable();
        stubTripLockable();
        stubZone();
        when(tripLocationRepository.findByTripIdAndRecordedAt(TRIP_ID, NOW))
                .thenReturn(Optional.empty())                    // çekirdekteki tekrar kontrolü: satır yok
                .thenReturn(Optional.of(existingLocation()));    // ihlal sonrası dış katman yeniden okuma
        when(tripLocationRepository.findFirstByTripIdOrderByRecordedAtDesc(TRIP_ID)).thenReturn(Optional.empty());
        when(tripLocationRepository.saveAndFlush(any(TripLocation.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "could not execute statement; CONSTRAINT uq_trip_locations_trip_time"));

        TripLocationResponse response = tripLocationService.recordLocation(TRIP_ID,
                request(TAKSIM_LAT, TAKSIM_LNG, "85", NOW));

        assertThat(response.duplicate()).isTrue();
        assertThat(response.id()).isEqualTo(100L);
        verify(tripWarningRepository, never()).save(any()); // ihlal anında uyarı yazılmadı
    }

    // --- 14. Bilinmeyen kısıt → rethrow (yanlış başarı üretme) ---
    @Test
    @DisplayName("Bilinmeyen kısıt ihlali → DataIntegrityViolationException dönüştürülmeden yeniden fırlatılır")
    void unknownConstraint_rethrown() {
        stubTripReadable();
        stubTripLockable();
        stubZone();
        when(tripLocationRepository.findByTripIdAndRecordedAt(TRIP_ID, NOW)).thenReturn(Optional.empty());
        when(tripLocationRepository.findFirstByTripIdOrderByRecordedAtDesc(TRIP_ID)).thenReturn(Optional.empty());
        when(tripLocationRepository.saveAndFlush(any(TripLocation.class)))
                .thenThrow(new DataIntegrityViolationException("violates some_other_constraint"));

        assertThatThrownBy(() -> tripLocationService.recordLocation(TRIP_ID,
                request(TAKSIM_LAT, TAKSIM_LNG, "85", NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(tripWarningRepository, never()).save(any());
    }

    // --- (a) Endpoint yolu: ÖNCE kilit, SONRA yazım (InOrder) ---
    @Test
    @DisplayName("recordLocation: önce findByIdForUpdate ile kilit alınır, sonra konum yazılır")
    void recordLocation_locksBeforeWrite() {
        stubHappyPath(NOW);

        tripLocationService.recordLocation(TRIP_ID, request(PENDIK_LAT, PENDIK_LNG, "50", NOW));

        InOrder inOrder = inOrder(tripRepository, tripLocationRepository);
        inOrder.verify(tripRepository).findByIdForUpdate(TRIP_ID);
        inOrder.verify(tripLocationRepository).saveAndFlush(any(TripLocation.class));
    }

    // --- (b) Çekirdek doğrudan çağrılırsa kilit ALMAZ (Adım 13 yeniden kullanımı) ---
    @Test
    @DisplayName("appendLocation doğrudan çağrılınca findByIdForUpdate ÇAĞRILMAZ (kilit çağrandadır)")
    void appendLocation_directCall_doesNotLock() {
        stubZone();
        when(tripLocationRepository.findByTripIdAndRecordedAt(TRIP_ID, NOW)).thenReturn(Optional.empty());
        when(tripLocationRepository.findFirstByTripIdOrderByRecordedAtDesc(TRIP_ID)).thenReturn(Optional.empty());
        stubSaveAssignsId();

        TripLocationWriter.LocationRecordResult result = tripLocationWriter.appendLocation(
                trip, TAKSIM_LAT, TAKSIM_LNG, new BigDecimal("85"), NOW);

        assertThat(result.duplicate()).isFalse();
        verify(tripRepository, never()).findByIdForUpdate(any());
        verify(tripWarningRepository).save(any(TripWarning.class)); // 85 > 80
    }

    // --- (c) Hız null → uyarı yok ---
    @Test
    @DisplayName("Hız verilmediyse (null) uyarı yazılmaz")
    void nullSpeed_noWarning() {
        stubHappyPath(NOW);

        TripLocationResponse response = tripLocationService.recordLocation(TRIP_ID,
                request(TAKSIM_LAT, TAKSIM_LNG, null, NOW));

        assertThat(response.speedKmh()).isNull();
        verify(tripWarningRepository, never()).save(any());
    }

    // --- (d) GET locations / warnings: olmayan trip → 404 ---
    @Test
    @DisplayName("GET locations ve GET warnings: olmayan trip → 404 TRIP_NOT_FOUND")
    void getLocations_getWarnings_tripNotFound() {
        when(tripRepository.existsById(TRIP_ID)).thenReturn(false);

        assertRejected(HttpStatus.NOT_FOUND, "TRIP_NOT_FOUND", () -> tripLocationService.getLocations(TRIP_ID));
        assertRejected(HttpStatus.NOT_FOUND, "TRIP_NOT_FOUND", () -> tripLocationService.getWarnings(TRIP_ID));
    }

    // --- (e) Dış katman @Transactional DEĞİL; writer yöntemleri transaction'lı ---
    @Test
    @DisplayName("TripLocationService.recordLocation @Transactional DEĞİL (sınıf düzeyinde de yok); writer transaction'lı")
    void recordLocation_isNotTransactional() throws Exception {
        Method method = TripLocationService.class.getMethod("recordLocation", Long.class, LocationRecordRequest.class);
        assertThat(method.getAnnotation(Transactional.class)).isNull();
        assertThat(TripLocationService.class.getAnnotation(Transactional.class)).isNull();

        // Writer: hem endpoint yolu hem çekirdek transaction içinde çalışır
        assertThat(TripLocationWriter.class.getMethod("recordLocation",
                Long.class, double.class, double.class, BigDecimal.class, Instant.class)
                .getAnnotation(Transactional.class)).isNotNull();
        assertThat(TripLocationWriter.class.getMethod("appendLocation",
                Trip.class, double.class, double.class, BigDecimal.class, Instant.class)
                .getAnnotation(Transactional.class)).isNotNull();
    }

    // --- (f) Nanosaniyeli recordedAt → mikrosaniyeye indirgenir ---
    @Test
    @DisplayName("Nanosaniyeli recordedAt mikrosaniyeye indirgenerek kontrol edilir ve yazılır")
    void nanoRecordedAt_truncatedToMicros() {
        Instant nanoTime = NOW.plusNanos(500); // 10:00:00.000000500
        stubTripReadable();
        stubTripLockable();
        stubZone();
        // Tekrar kontrolü İNDİRGENMİŞ değerle yapılır; stub bu yüzden NOW'dur (nanosaniyeyle çağrılsaydı eşleşmezdi)
        when(tripLocationRepository.findByTripIdAndRecordedAt(TRIP_ID, NOW)).thenReturn(Optional.empty());
        when(tripLocationRepository.findFirstByTripIdOrderByRecordedAtDesc(TRIP_ID)).thenReturn(Optional.empty());
        stubSaveAssignsId();

        TripLocationResponse response = tripLocationService.recordLocation(TRIP_ID,
                request(TAKSIM_LAT, TAKSIM_LNG, "50", nanoTime));

        ArgumentCaptor<TripLocation> locationCaptor = ArgumentCaptor.forClass(TripLocation.class);
        verify(tripLocationRepository).saveAndFlush(locationCaptor.capture());
        assertThat(locationCaptor.getValue().getRecordedAt()).isEqualTo(NOW);
        assertThat(response.recordedAt()).isEqualTo(NOW);
    }

    // --- (g) Çekirdek doğrudan çağrı: nanosaniyeli recordedAt → mikrosaniyeye kırpılır ---
    @Test
    @DisplayName("appendLocation doğrudan çağrı: 123456789ns recordedAt, tekrar kontrolünde ve yazımda 123456µs'ye kırpılır")
    void appendLocation_directCall_nanoRecordedAt_truncatedToMicros() {
        Instant nanoTime = Instant.parse("2026-01-01T10:00:00.123456789Z");
        Instant microTime = Instant.parse("2026-01-01T10:00:00.123456Z");
        stubZone();
        // Tekrar kontrolü KIRPMIŞ değerle yapılır; stub bu yüzden microTime'dır (nanoTime ile çağrılsaydı eşleşmezdi)
        when(tripLocationRepository.findByTripIdAndRecordedAt(TRIP_ID, microTime)).thenReturn(Optional.empty());
        when(tripLocationRepository.findFirstByTripIdOrderByRecordedAtDesc(TRIP_ID)).thenReturn(Optional.empty());
        stubSaveAssignsId();

        TripLocationWriter.LocationRecordResult result = tripLocationWriter.appendLocation(
                trip, TAKSIM_LAT, TAKSIM_LNG, new BigDecimal("50"), nanoTime);

        assertThat(result.duplicate()).isFalse();

        ArgumentCaptor<Instant> lookupCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(tripLocationRepository).findByTripIdAndRecordedAt(eq(TRIP_ID), lookupCaptor.capture());
        assertThat(lookupCaptor.getValue()).isEqualTo(microTime); // ...123456Z

        ArgumentCaptor<TripLocation> locationCaptor = ArgumentCaptor.forClass(TripLocation.class);
        verify(tripLocationRepository).saveAndFlush(locationCaptor.capture());
        assertThat(locationCaptor.getValue().getRecordedAt()).isEqualTo(microTime); // ...123456Z
    }

    // --- GET başarı yolları: eşleme ve sıra ---
    @Test
    @DisplayName("GET locations/warnings: zaman sıralı liste doğru eşlenir, duplicate=false")
    void getLocations_getWarnings_success() {
        when(tripRepository.existsById(TRIP_ID)).thenReturn(true);
        TripLocation first = existingLocation();
        first.setId(101L);
        first.setRecordedAt(NOW.minusSeconds(120));
        TripLocation second = existingLocation();
        second.setId(102L);
        second.setRecordedAt(NOW.minusSeconds(60));
        when(tripLocationRepository.findByTripIdOrderByRecordedAtAsc(TRIP_ID))
                .thenReturn(List.of(first, second));

        TripWarning warning = new TripWarning();
        warning.setId(1L);
        warning.setTrip(trip);
        warning.setWarningType(WarningType.SPEED_LIMIT);
        warning.setMessage("Hız limiti aşıldı.");
        warning.setCreatedAt(NOW);
        when(tripWarningRepository.findByTripIdOrderByCreatedAtAsc(TRIP_ID)).thenReturn(List.of(warning));

        List<TripLocationResponse> locations = tripLocationService.getLocations(TRIP_ID);
        assertThat(locations).hasSize(2);
        assertThat(locations.get(0).id()).isEqualTo(101L);
        assertThat(locations.get(0).duplicate()).isFalse();
        assertThat(locations.get(1).id()).isEqualTo(102L);

        List<TripWarningResponse> warnings = tripLocationService.getWarnings(TRIP_ID);
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).warningType()).isEqualTo(WarningType.SPEED_LIMIT);
        assertThat(warnings.get(0).id()).isEqualTo(1L);
    }

    // --- yardımcılar ---

    /** Red durumlarını doğrular: beklenen HttpStatus + kod VE hiçbir şey yazılmadığı (never save). */
    private void assertRejected(HttpStatus status, String code, Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(status);
                    assertThat(apiEx.getCode()).isEqualTo(code);
                });
        verify(tripLocationRepository, never()).saveAndFlush(any());
        verify(tripLocationRepository, never()).save(any());
        verify(tripWarningRepository, never()).save(any());
    }

    private void stubTripReadable() {
        when(tripRepository.findById(TRIP_ID)).thenReturn(Optional.of(trip));
    }

    private void stubTripLockable() {
        when(tripRepository.findByIdForUpdate(TRIP_ID)).thenReturn(Optional.of(trip));
    }

    private void stubZone() {
        when(vehicleZoneRepository.findByVehicleId(VEHICLE_ID)).thenReturn(List.of(zone));
    }

    private void stubSaveAssignsId() {
        when(tripLocationRepository.saveAndFlush(any(TripLocation.class))).thenAnswer(inv -> {
            TripLocation loc = inv.getArgument(0);
            loc.setId(100L);
            return loc;
        });
    }

    private void stubHappyPath(Instant recordedAt) {
        stubTripReadable();
        stubTripLockable();
        stubZone();
        when(tripLocationRepository.findByTripIdAndRecordedAt(TRIP_ID, recordedAt)).thenReturn(Optional.empty());
        when(tripLocationRepository.findFirstByTripIdOrderByRecordedAtDesc(TRIP_ID)).thenReturn(Optional.empty());
        stubSaveAssignsId();
    }

    private LocationRecordRequest request(double lat, double lng, String speed, Instant recordedAt) {
        return new LocationRecordRequest(lat, lng, speed != null ? new BigDecimal(speed) : null, recordedAt);
    }

    private TripLocation existingLocation() {
        TripLocation location = new TripLocation();
        location.setId(100L);
        location.setTrip(trip);
        location.setLatitude(TAKSIM_LAT);
        location.setLongitude(TAKSIM_LNG);
        location.setRecordedAt(NOW);
        location.setCity("İstanbul");
        location.setSpeedKmh(new BigDecimal("85"));
        return location;
    }
}

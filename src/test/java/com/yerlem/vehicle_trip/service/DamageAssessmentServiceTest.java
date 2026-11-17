package com.yerlem.vehicle_trip.service;

import com.yerlem.vehicle_trip.config.DamageProperties;
import com.yerlem.vehicle_trip.entity.Trip;
import com.yerlem.vehicle_trip.entity.TripDamage;
import com.yerlem.vehicle_trip.entity.TripPhoto;
import com.yerlem.vehicle_trip.enums.DamageKind;
import com.yerlem.vehicle_trip.enums.PhotoPhase;
import com.yerlem.vehicle_trip.enums.PhotoType;
import com.yerlem.vehicle_trip.integration.MockDamageDetectionService;
import com.yerlem.vehicle_trip.repository.TripDamageRepository;
import com.yerlem.vehicle_trip.repository.TripPhotoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Adım 17 / B8 — DamageAssessmentService: çift kuralı (START+END birlikte), config
 * maliyetleriyle toplam (literal 950.00 = 500.00 + 300.00 + 150.00), mükerrer yazım
 * koruması. Tespit motoru GERÇEK MockDamageDetectionService'tir (belirlenimci kural
 * uçtan uca doğrulanır), Clock sabittir.
 */
@ExtendWith(MockitoExtension.class)
class DamageAssessmentServiceTest {

    private static final Long TRIP_ID = 10L;
    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    @Mock
    private TripPhotoRepository tripPhotoRepository;
    @Mock
    private TripDamageRepository tripDamageRepository;

    private DamageAssessmentService service;
    private Trip trip;

    @BeforeEach
    void setUp() {
        Map<PhotoType, BigDecimal> costs = new EnumMap<>(PhotoType.class);
        costs.put(PhotoType.EXTERIOR, new BigDecimal("500.00"));
        costs.put(PhotoType.FRONT_SEAT, new BigDecimal("300.00"));
        costs.put(PhotoType.BACK_SEAT, new BigDecimal("300.00"));
        service = new DamageAssessmentService(tripPhotoRepository, tripDamageRepository,
                new MockDamageDetectionService(), new DamageProperties(costs, new BigDecimal("150.00")),
                Clock.fixed(NOW, ZoneOffset.UTC));
        trip = new Trip();
        trip.setId(TRIP_ID);
    }

    @Test
    @DisplayName("hiç fotoğraf yok → 0.00, hasar kaydı yazılmaz")
    void noPhotos_returnsZero() {
        stubPairs(Map.of(), Map.of());

        assertThat(service.assess(trip)).isEqualByComparingTo("0.00");
        verify(tripDamageRepository, never()).save(any());
    }

    @Test
    @DisplayName("yalnızca START (END eksik) veya yalnızca END → karşılaştırma yok, 0.00")
    void incompletePair_isSkipped() {
        stubPairs(Map.of(PhotoType.EXTERIOR, "mock://start.jpg"), Map.of());
        assertThat(service.assess(trip)).isEqualByComparingTo("0.00");

        stubPairs(Map.of(), Map.of(PhotoType.EXTERIOR, "mock://end-damage.jpg"));
        assertThat(service.assess(trip)).isEqualByComparingTo("0.00");
        verify(tripDamageRepository, never()).save(any());
    }

    @Test
    @DisplayName("EXTERIOR hasar çifti → TripDamage(DAMAGE, 500.00) + toplam 500.00, detectedAt sabit saat")
    void exteriorDamage_returnsConfiguredCost() {
        stubPairs(Map.of(PhotoType.EXTERIOR, "mock://start-ext.jpg"),
                Map.of(PhotoType.EXTERIOR, "mock://end-damage-ext.jpg"));
        when(tripDamageRepository.existsByTripIdAndPhotoType(TRIP_ID, PhotoType.EXTERIOR)).thenReturn(false);

        BigDecimal total = service.assess(trip);

        assertThat(total).isEqualByComparingTo("500.00");
        ArgumentCaptor<TripDamage> captor = ArgumentCaptor.forClass(TripDamage.class);
        verify(tripDamageRepository).save(captor.capture());
        TripDamage damage = captor.getValue();
        assertThat(damage.getDamageKind()).isEqualTo(DamageKind.DAMAGE);
        assertThat(damage.getPhotoType()).isEqualTo(PhotoType.EXTERIOR);
        assertThat(damage.getCost()).isEqualByComparingTo("500.00");
        assertThat(damage.getTrip()).isSameAs(trip);
        assertThat(damage.getDetectedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("üç tür birden: 500.00 (EXTERIOR damage) + 300.00 (BACK_SEAT damage) + 150.00 (eksik) = 950.00")
    void allThreeTypes_sumWithLiterals() {
        Map<PhotoType, String> start = new EnumMap<>(PhotoType.class);
        start.put(PhotoType.EXTERIOR, "mock://s-ext.jpg");
        start.put(PhotoType.BACK_SEAT, "mock://s-back.jpg");
        start.put(PhotoType.FRONT_SEAT, "mock://s-front.jpg");
        Map<PhotoType, String> end = new EnumMap<>(PhotoType.class);
        end.put(PhotoType.EXTERIOR, "mock://e-damage.jpg");
        end.put(PhotoType.BACK_SEAT, "mock://e-damage-back.jpg");
        end.put(PhotoType.FRONT_SEAT, "mock://e-missing-part.jpg");
        stubPairs(start, end);
        for (PhotoType type : PhotoType.values()) {
            when(tripDamageRepository.existsByTripIdAndPhotoType(TRIP_ID, type)).thenReturn(false);
        }

        assertThat(service.assess(trip)).isEqualByComparingTo("950.00");
        verify(tripDamageRepository, times(3)).save(any(TripDamage.class));
    }

    @Test
    @DisplayName("bulgu temizse kayıt yazılmaz → 0.00")
    void cleanPair_writesNothing() {
        stubPairs(Map.of(PhotoType.EXTERIOR, "mock://s.jpg"), Map.of(PhotoType.EXTERIOR, "mock://e-clean.jpg"));
        when(tripDamageRepository.existsByTripIdAndPhotoType(TRIP_ID, PhotoType.EXTERIOR)).thenReturn(false);

        assertThat(service.assess(trip)).isEqualByComparingTo("0.00");
        verify(tripDamageRepository, never()).save(any());
    }

    @Test
    @DisplayName("aynı tür için hasar kaydı ZATEN varsa yeniden yazılmaz (idempotent assess)")
    void existingDamage_isNotRewritten() {
        stubPairs(Map.of(PhotoType.EXTERIOR, "mock://s.jpg"), Map.of(PhotoType.EXTERIOR, "mock://e-damage.jpg"));
        when(tripDamageRepository.existsByTripIdAndPhotoType(TRIP_ID, PhotoType.EXTERIOR)).thenReturn(true);

        assertThat(service.assess(trip)).isEqualByComparingTo("0.00");
        verify(tripDamageRepository, never()).save(any());
    }

    /** Her tür için START/END find stub'ları (bulunamayan → empty). */
    private void stubPairs(Map<PhotoType, String> startUrls, Map<PhotoType, String> endUrls) {
        for (PhotoType type : PhotoType.values()) {
            when(tripPhotoRepository.findByTripIdAndPhaseAndPhotoType(TRIP_ID, PhotoPhase.START, type))
                    .thenReturn(Optional.ofNullable(startUrls.get(type)).map(url -> photo(PhotoPhase.START, type, url)));
            when(tripPhotoRepository.findByTripIdAndPhaseAndPhotoType(TRIP_ID, PhotoPhase.END, type))
                    .thenReturn(Optional.ofNullable(endUrls.get(type)).map(url -> photo(PhotoPhase.END, type, url)));
        }
    }

    private TripPhoto photo(PhotoPhase phase, PhotoType type, String url) {
        TripPhoto photo = new TripPhoto();
        photo.setTrip(trip);
        photo.setPhase(phase);
        photo.setPhotoType(type);
        photo.setUrl(url);
        return photo;
    }
}

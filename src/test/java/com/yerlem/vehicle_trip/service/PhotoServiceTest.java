package com.yerlem.vehicle_trip.service;

import com.yerlem.vehicle_trip.dto.PhotoAddRequest;
import com.yerlem.vehicle_trip.dto.PhotoResponse;
import com.yerlem.vehicle_trip.entity.Trip;
import com.yerlem.vehicle_trip.entity.TripPhoto;
import com.yerlem.vehicle_trip.enums.PhotoPhase;
import com.yerlem.vehicle_trip.enums.PhotoType;
import com.yerlem.vehicle_trip.enums.TripStatus;
import com.yerlem.vehicle_trip.exception.ApiException;
import com.yerlem.vehicle_trip.repository.TripPhotoRepository;
import com.yerlem.vehicle_trip.repository.TripRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Adım 17 / B8 — PhotoService kuralları: trip kilidi, faz/durum matrisi, mükerrer 409,
 * geçersiz faz/tür/url 400, trip yok 404, Locale.ROOT normalizasyonu (tr-TR yerel
 * ayarında bile "exterior" → EXTERIOR olmalı, yoksa İ hatası üretirdi).
 */
@ExtendWith(MockitoExtension.class)
class PhotoServiceTest {

    private static final Long TRIP_ID = 10L;

    @Mock
    private TripRepository tripRepository;
    @Mock
    private TripPhotoRepository tripPhotoRepository;

    private PhotoService service;
    private Trip trip;
    private Locale defaultLocale;

    @BeforeEach
    void setUp() {
        service = new PhotoService(tripRepository, tripPhotoRepository);
        trip = new Trip();
        trip.setId(TRIP_ID);
        trip.setStatus(TripStatus.ACTIVE);
        defaultLocale = Locale.getDefault();
    }

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(defaultLocale);
    }

    @Test
    @DisplayName("ACTIVE trip'e END fotoğrafı: trip satırı FOR UPDATE kilitlenir, 201 kaydı döner")
    void addPhoto_endOnActive_succeeds_andLocksTrip() {
        givenTrip();
        when(tripPhotoRepository.existsByTripIdAndPhaseAndPhotoType(TRIP_ID, PhotoPhase.END, PhotoType.EXTERIOR))
                .thenReturn(false);
        when(tripPhotoRepository.save(any(TripPhoto.class))).thenAnswer(inv -> {
            TripPhoto photo = inv.getArgument(0);
            photo.setId(99L);
            return photo;
        });

        PhotoResponse response = service.addPhoto(TRIP_ID, request("END", "EXTERIOR", "mock://end.jpg"));

        verify(tripRepository).findByIdForUpdate(TRIP_ID);
        assertThat(response.id()).isEqualTo(99L);
        assertThat(response.phase()).isEqualTo(PhotoPhase.END);
        ArgumentCaptor<TripPhoto> captor = ArgumentCaptor.forClass(TripPhoto.class);
        verify(tripPhotoRepository).save(captor.capture());
        assertThat(captor.getValue().getTrip()).isSameAs(trip);
        assertThat(captor.getValue().getUploadedAt()).isNotNull();
    }

    @Test
    @DisplayName("CREATED trip: START kabul (ACTIVE/CREATED), END reddedilir → 409 INVALID_TRIP_STATE")
    void addPhoto_createdTrip_startOk_endRejected() {
        trip.setStatus(TripStatus.CREATED);
        givenTrip();
        when(tripPhotoRepository.existsByTripIdAndPhaseAndPhotoType(TRIP_ID, PhotoPhase.START, PhotoType.FRONT_SEAT))
                .thenReturn(false);
        when(tripPhotoRepository.save(any(TripPhoto.class))).thenAnswer(inv -> inv.getArgument(0));

        service.addPhoto(TRIP_ID, request("START", "FRONT_SEAT", "mock://front.jpg"));

        assertThatThrownBy(() -> service.addPhoto(TRIP_ID, request("END", "BACK_SEAT", "mock://back.jpg")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(((ApiException) ex).getCode()).isEqualTo("INVALID_TRIP_STATE");
                });
    }

    @Test
    @DisplayName("COMPLETED trip: hiçbir faz kabul edilmez → 409 INVALID_TRIP_STATE")
    void addPhoto_completedTrip_rejected() {
        trip.setStatus(TripStatus.COMPLETED);
        givenTrip();

        assertThatThrownBy(() -> service.addPhoto(TRIP_ID, request("START", "EXTERIOR", "mock://x.jpg")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("INVALID_TRIP_STATE"));
        verify(tripPhotoRepository, never()).save(any());
    }

    @Test
    @DisplayName("mükerrer (trip, faz, tür) → 409 PHOTO_ALREADY_EXISTS, kayıt yazılmaz")
    void addPhoto_duplicate_rejected() {
        givenTrip();
        when(tripPhotoRepository.existsByTripIdAndPhaseAndPhotoType(TRIP_ID, PhotoPhase.START, PhotoType.EXTERIOR))
                .thenReturn(true);

        assertThatThrownBy(() -> service.addPhoto(TRIP_ID, request("START", "EXTERIOR", "mock://dup.jpg")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(((ApiException) ex).getCode()).isEqualTo("PHOTO_ALREADY_EXISTS");
                });
        verify(tripPhotoRepository, never()).save(any());
    }

    @Test
    @DisplayName("geçersiz faz/tür, boş url ve 501 karakterlik url → 400 INVALID_PHOTO")
    void addPhoto_invalidValues_400() {
        givenTrip();

        assertThatThrownBy(() -> service.addPhoto(TRIP_ID, request("ORTA", "EXTERIOR", "mock://x")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("INVALID_PHOTO"));
        assertThatThrownBy(() -> service.addPhoto(TRIP_ID, request("START", "TAVAN", "mock://x")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("INVALID_PHOTO"));
        assertThatThrownBy(() -> service.addPhoto(TRIP_ID, request("START", "EXTERIOR", "   ")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("INVALID_PHOTO"));
        assertThatThrownBy(() -> service.addPhoto(TRIP_ID,
                request("START", "EXTERIOR", "m".repeat(501))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("INVALID_PHOTO"));
        verify(tripPhotoRepository, never()).save(any());
    }

    @Test
    @DisplayName("trip yok → 404 TRIP_NOT_FOUND (kilit denemesi boş döner)")
    void addPhoto_tripNotFound_404() {
        when(tripRepository.findByIdForUpdate(TRIP_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addPhoto(TRIP_ID, request("START", "EXTERIOR", "mock://x")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("tr-TR yerel ayarında bile normalizasyon Locale.ROOT: ' end ' → END, 'exterior' → EXTERIOR; url trimlenir")
    void addPhoto_normalizationUnderTurkishLocale() {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        givenTrip();
        when(tripPhotoRepository.existsByTripIdAndPhaseAndPhotoType(TRIP_ID, PhotoPhase.END, PhotoType.EXTERIOR))
                .thenReturn(false);
        when(tripPhotoRepository.save(any(TripPhoto.class))).thenAnswer(inv -> inv.getArgument(0));

        PhotoResponse response = service.addPhoto(TRIP_ID, request(" end ", " exterior ", "  mock://end.jpg  "));

        assertThat(response.phase()).isEqualTo(PhotoPhase.END);
        assertThat(response.photoType()).isEqualTo(PhotoType.EXTERIOR);
        assertThat(response.url()).isEqualTo("mock://end.jpg");
    }

    @Test
    @DisplayName("liste trip yoksa 404; varsa depo sırası (faz, tür) olduğu gibi döner")
    void getPhotos_sorted_fromRepository() {
        when(tripRepository.existsById(TRIP_ID)).thenReturn(true);
        TripPhoto photo = new TripPhoto();
        photo.setId(1L);
        photo.setPhase(PhotoPhase.START);
        photo.setPhotoType(PhotoType.EXTERIOR);
        photo.setUrl("mock://s.jpg");
        when(tripPhotoRepository.findByTripIdOrderByPhaseAscPhotoTypeAsc(TRIP_ID)).thenReturn(List.of(photo));

        assertThat(service.getPhotos(TRIP_ID)).hasSize(1);

        when(tripRepository.existsById(404L)).thenReturn(false);
        assertThatThrownBy(() -> service.getPhotos(404L))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    private void givenTrip() {
        when(tripRepository.findByIdForUpdate(TRIP_ID)).thenReturn(Optional.of(trip));
    }

    private PhotoAddRequest request(String phase, String type, String url) {
        return new PhotoAddRequest(phase, type, url);
    }
}

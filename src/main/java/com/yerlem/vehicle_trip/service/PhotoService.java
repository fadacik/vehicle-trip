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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Seyahat fotoğrafları (Adım 17 / B3, D22). Gerçek dosya yüklenmez; yalnızca referans
 * (URL/yol) saklanır. Ekleme, trip satırını findByIdForUpdate ile KİLİTLEYerek yapılır:
 * aynı (trip, faz, tür) yarışında ikinci istek ya 409 PHOTO_ALREADY_EXISTS görür ya
 * DB unique kısıtıyla durur — mükerrer satır oluşmaz.
 *
 * Kurallar: START fotoğrafı yalnızca CREATED/ACTIVE trip'e, END yalnızca ACTIVE trip'e;
 * faz/tür trim + toUpperCase(Locale.ROOT) ile normalleştirilir; geçersiz değer, boş ya
 * da 500+ karakter url → 400 INVALID_PHOTO.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PhotoService {

    private static final int URL_MAX_LENGTH = 500;

    private final TripRepository tripRepository;
    private final TripPhotoRepository tripPhotoRepository;

    @Transactional
    public PhotoResponse addPhoto(Long tripId, PhotoAddRequest request) {
        Trip trip = tripRepository.findByIdForUpdate(tripId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TRIP_NOT_FOUND",
                        "Seyahat bulunamadı."));

        PhotoPhase phase = parsePhase(request.phase());
        PhotoType photoType = parseType(request.photoType());
        String url = validateUrl(request.url());

        if (!phaseAllowed(phase, trip.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRIP_STATE",
                    "Fotoğraf eklenemiyor: " + phase + " fotoğrafı " + describeAllowedStates(phase)
                            + " durumundaki seyahate eklenebilir (mevcut durum: " + trip.getStatus() + ").");
        }
        if (tripPhotoRepository.existsByTripIdAndPhaseAndPhotoType(tripId, phase, photoType)) {
            throw new ApiException(HttpStatus.CONFLICT, "PHOTO_ALREADY_EXISTS",
                    phase + " fazında " + photoType + " fotoğrafı zaten yüklü; her kombinasyon tek sefer yüklenir.");
        }

        TripPhoto photo = new TripPhoto();
        photo.setTrip(trip);
        photo.setPhase(phase);
        photo.setPhotoType(photoType);
        photo.setUrl(url);
        photo.setUploadedAt(Instant.now());
        TripPhoto saved = tripPhotoRepository.save(photo);

        log.info("Fotoğraf yüklendi. tripId={}, phase={}, photoType={}", tripId, phase, photoType);
        return PhotoResponse.from(saved);
    }

    /** Deterministik liste: faz artan, sonra tür artan. */
    @Transactional(readOnly = true)
    public List<PhotoResponse> getPhotos(Long tripId) {
        if (!tripRepository.existsById(tripId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TRIP_NOT_FOUND", "Seyahat bulunamadı.");
        }
        return tripPhotoRepository.findByTripIdOrderByPhaseAscPhotoTypeAsc(tripId).stream()
                .map(PhotoResponse::from)
                .toList();
    }

    private boolean phaseAllowed(PhotoPhase phase, TripStatus status) {
        return phase == PhotoPhase.START
                ? status == TripStatus.CREATED || status == TripStatus.ACTIVE
                : status == TripStatus.ACTIVE;
    }

    private String describeAllowedStates(PhotoPhase phase) {
        return phase == PhotoPhase.START ? "CREATED veya ACTIVE" : "yalnızca ACTIVE";
    }

    private PhotoPhase parsePhase(String raw) {
        return parseEnum(raw, PhotoPhase.class, "Faz geçersiz: yalnızca START veya END olabilir.");
    }

    private PhotoType parseType(String raw) {
        return parseEnum(raw, PhotoType.class,
                "Fotoğraf türü geçersiz: yalnızca FRONT_SEAT, BACK_SEAT veya EXTERIOR olabilir.");
    }

    private <E extends Enum<E>> E parseEnum(String raw, Class<E> type, String turkishMessage) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PHOTO", turkishMessage);
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PHOTO", turkishMessage);
        }
    }

    private String validateUrl(String url) {
        String trimmed = url == null ? "" : url.trim();
        if (trimmed.isEmpty() || trimmed.length() > URL_MAX_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PHOTO",
                    "Fotoğraf url'si boş olamaz ve en fazla " + URL_MAX_LENGTH + " karakter olabilir.");
        }
        return trimmed;
    }
}

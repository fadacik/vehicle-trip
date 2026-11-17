package com.yerlem.vehicle_trip.dto;

import com.yerlem.vehicle_trip.entity.TripPhoto;
import com.yerlem.vehicle_trip.enums.PhotoPhase;
import com.yerlem.vehicle_trip.enums.PhotoType;

import java.time.Instant;

/** Yüklenen fotoğraf yanıtı (entity dışarı dönmez). */
public record PhotoResponse(
        Long id,
        PhotoPhase phase,
        PhotoType photoType,
        String url,
        Instant uploadedAt) {

    public static PhotoResponse from(TripPhoto photo) {
        return new PhotoResponse(photo.getId(), photo.getPhase(), photo.getPhotoType(),
                photo.getUrl(), photo.getUploadedAt());
    }
}

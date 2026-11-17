package com.yerlem.vehicle_trip.dto;

import com.yerlem.vehicle_trip.entity.FaultReport;
import com.yerlem.vehicle_trip.entity.TripDamage;
import com.yerlem.vehicle_trip.entity.TripDoorAction;
import com.yerlem.vehicle_trip.entity.TripPhoto;
import com.yerlem.vehicle_trip.entity.TripWarning;
import com.yerlem.vehicle_trip.enums.DoorAction;
import com.yerlem.vehicle_trip.enums.DoorActionReason;
import com.yerlem.vehicle_trip.enums.DoorActionStatus;

import java.time.Instant;

/**
 * Zaman çizelgesi olayı (Adım 13). Olay türleri: TRIP_CREATED, TRIP_STARTED,
 * FAULT_REPORTED, WARNING, TRIP_COMPLETED. {@code type} yalnızca FAULT_REPORTED
 * (arıza türü) ve WARNING (uyarı türü) olaylarında doludur; {@code message} arıza
 * açıklaması veya uyarı mesajıdır, diğer olaylarda null'dur.
 */
public record TimelineEventResponse(
        String eventType,
        Instant timestamp,
        String type,
        String message) {

    public static TimelineEventResponse tripCreated(Instant createdAt) {
        return new TimelineEventResponse("TRIP_CREATED", createdAt, null, null);
    }

    public static TimelineEventResponse tripStarted(Instant startedAt) {
        return new TimelineEventResponse("TRIP_STARTED", startedAt, null, null);
    }

    public static TimelineEventResponse faultReported(FaultReport report) {
        return new TimelineEventResponse("FAULT_REPORTED", report.getReportedAt(),
                report.getFaultType().name(), report.getDescription());
    }

    public static TimelineEventResponse warning(TripWarning warning) {
        return new TimelineEventResponse("WARNING", warning.getCreatedAt(),
                warning.getWarningType().name(), warning.getMessage());
    }

    public static TimelineEventResponse tripCompleted(Instant completedAt) {
        return new TimelineEventResponse("TRIP_COMPLETED", completedAt, null, null);
    }

    /** Kapı denetim kaydından olay üretir: DOOR_UNLOCKED / DOOR_LOCKED / DOOR_ACTION_FAILED / DOOR_COMPENSATED. */
    public static TimelineEventResponse doorAction(TripDoorAction action) {
        String eventType;
        if (action.getReason() == DoorActionReason.COMPENSATION) {
            eventType = "DOOR_COMPENSATED";
        } else if (action.getStatus() == DoorActionStatus.FAILED) {
            eventType = "DOOR_ACTION_FAILED";
        } else if (action.getAction() == DoorAction.UNLOCK) {
            eventType = "DOOR_UNLOCKED";
        } else {
            eventType = "DOOR_LOCKED";
        }
        return new TimelineEventResponse(eventType, action.getCreatedAt(), action.getAction().name(),
                action.getMessage());
    }

    /** Fotoğraf yükleme olayı: type = fotoğraf türü, message = faz. */
    public static TimelineEventResponse photoAdded(TripPhoto photo) {
        return new TimelineEventResponse("PHOTO_ADDED", photo.getUploadedAt(),
                photo.getPhotoType().name(), photo.getPhase().name());
    }

    /** Hasar tespit olayı: type = hasar türü, message = açıklama. */
    public static TimelineEventResponse damageDetected(TripDamage damage) {
        return new TimelineEventResponse("DAMAGE_DETECTED", damage.getDetectedAt(),
                damage.getDamageKind().name(), damage.getDescription());
    }
}

package com.yerlem.vehicle_trip.service;

import com.yerlem.vehicle_trip.entity.Trip;
import com.yerlem.vehicle_trip.entity.TripDoorAction;
import com.yerlem.vehicle_trip.entity.Vehicle;
import com.yerlem.vehicle_trip.enums.DoorAction;
import com.yerlem.vehicle_trip.enums.DoorActionReason;
import com.yerlem.vehicle_trip.enums.DoorActionStatus;
import com.yerlem.vehicle_trip.repository.TripDoorActionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Kapı işlem denetim kaydının AYRI transaction yazımları (Adım 17 / A5, D20).
 * Ayrı bean'dir: REQUIRES_NEW self-invocation ile çağrılırsa propagation etkisiz
 * kalacağı için coordinator bu bean ÜZERİNDEN çağırır.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DoorActionRecorder {

    private static final int MESSAGE_MAX_LENGTH = 500;

    private final TripDoorActionRepository tripDoorActionRepository;

    /** Başarısız dış çağrı kaydı: ana işlem geri alınsa da denetim izi KALICI olmalı. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailed(Trip trip, Vehicle vehicle, DoorAction action, DoorActionReason reason, String message) {
        save(trip, vehicle, action, DoorActionStatus.FAILED, reason, message);
    }

    /** Telafi (compensation) sonucu kaydı; başarısız ana işlem sonrası en iyi çaba. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordCompensation(Trip trip, Vehicle vehicle, DoorAction compensatedAction,
                                   DoorActionStatus status, String message) {
        save(trip, vehicle, compensatedAction, status, DoorActionReason.COMPENSATION, message);
    }

    private void save(Trip trip, Vehicle vehicle, DoorAction action, DoorActionStatus status,
                      DoorActionReason reason, String message) {
        TripDoorAction record = new TripDoorAction();
        record.setTrip(trip);
        record.setVehicle(vehicle);
        record.setAction(action);
        record.setStatus(status);
        record.setReason(reason);
        record.setMessage(truncate(message));
        record.setCreatedAt(Instant.now());
        tripDoorActionRepository.save(record);
        log.info("Kapı işlem kaydı yazıldı (ayrı transaction). tripId={}, action={}, status={}, reason={}",
                trip.getId(), action, status, reason);
    }

    private String truncate(String message) {
        if (message == null || message.length() <= MESSAGE_MAX_LENGTH) {
            return message;
        }
        return message.substring(0, MESSAGE_MAX_LENGTH);
    }
}

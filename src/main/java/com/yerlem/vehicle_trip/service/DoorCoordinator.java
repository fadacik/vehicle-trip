package com.yerlem.vehicle_trip.service;

import com.yerlem.vehicle_trip.entity.Trip;
import com.yerlem.vehicle_trip.entity.TripDoorAction;
import com.yerlem.vehicle_trip.entity.Vehicle;
import com.yerlem.vehicle_trip.enums.DoorAction;
import com.yerlem.vehicle_trip.enums.DoorActionReason;
import com.yerlem.vehicle_trip.enums.DoorActionStatus;
import com.yerlem.vehicle_trip.exception.ApiException;
import com.yerlem.vehicle_trip.integration.DoorLockService;
import com.yerlem.vehicle_trip.integration.DoorServiceException;
import com.yerlem.vehicle_trip.repository.TripDoorActionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;

/**
 * Kapı dış çağrısının koordinasyonu (Adım 17 / A5, D20).
 *
 * Dış çağrı HER ZAMAN transaction'ın son adımıdır (çağıran servis öyle bağlar).
 * - Başarı: SUCCESS denetim kaydı ANA transaction'a yazılır (geri alınırsa iz de kalkar;
 *   böylece tekrar denemede tam bir başarılı kayıt kalır) + senkronizasyon varsa
 *   afterCompletion telafisi kaydedilir: ana işlem ROLLED_BACK ise ters kapı çağrısı
 *   yapılır ve sonucu REQUIRES_NEW ile COMPENSATION olarak yazılır (en iyi çaba).
 * - Başarısızlık: FAILED kaydı REQUIRES_NEW ile KALICI bırakılır (ayrı bean
 *   DoorActionRecorder — self-invocation yok), sonra 503 ApiException fırlatılır.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DoorCoordinator {

    private final DoorLockService doorLockService;
    private final TripDoorActionRepository tripDoorActionRepository;
    private final DoorActionRecorder doorActionRecorder;

    /** Seyahat başlangıcında kapı açma; hata → 503 DOOR_UNLOCK_FAILED. */
    public void unlockForTripStart(Trip trip, Vehicle vehicle) {
        callDoor(trip, vehicle, DoorAction.UNLOCK, DoorActionReason.TRIP_START,
                () -> doorLockService.unlock(vehicle.getId()),
                () -> doorLockService.lock(vehicle.getId()),
                "DOOR_UNLOCK_FAILED",
                "Araç kapısı açılamadı, seyahat başlatılamadı. Lütfen tekrar deneyebilirsiniz.");
    }

    /** Seyahat tamamlanmasında kapı kilitleme; hata → 503 DOOR_LOCK_FAILED. */
    public void lockForTripCompletion(Trip trip, Vehicle vehicle) {
        callDoor(trip, vehicle, DoorAction.LOCK, DoorActionReason.TRIP_COMPLETE,
                () -> doorLockService.lock(vehicle.getId()),
                () -> doorLockService.unlock(vehicle.getId()),
                "DOOR_LOCK_FAILED",
                "Araç kapısı kilitlenemedi, seyahat kapatılamadı. Lütfen tekrar deneyebilirsiniz.");
    }

    private void callDoor(Trip trip, Vehicle vehicle, DoorAction action, DoorActionReason reason,
                          Runnable externalCall, Runnable compensationCall,
                          String errorCode, String userMessage) {
        try {
            externalCall.run();
        } catch (DoorServiceException ex) {
            log.error("Kapı servisi çağrısı başarısız. tripId={}, vehicleId={}, action={}",
                    trip.getId(), vehicle.getId(), action, ex);
            doorActionRecorder.recordFailed(trip, vehicle, action, reason, ex.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, errorCode, userMessage);
        }
        // Telafi ÖNCE kaydedilir: SUCCESS denetim kaydı yazılırken hata olursa transaction
        // geri alınır ve afterCompletion telafiyi tetikler (kapı açık kalmaz). Kayıt sonrasına
        // bırakılırsa INSERT hatasında telafi hiç register edilmemiş olurdu.
        registerCompensationIfActive(trip, vehicle, action, compensationCall);
        recordSuccess(trip, vehicle, action, reason);
    }

    /** SUCCESS kaydı ana transaction'da: ana işlem geri alınırsa bu iz de geri alınır. */
    private void recordSuccess(Trip trip, Vehicle vehicle, DoorAction action, DoorActionReason reason) {
        TripDoorAction record = new TripDoorAction();
        record.setTrip(trip);
        record.setVehicle(vehicle);
        record.setAction(action);
        record.setStatus(DoorActionStatus.SUCCESS);
        record.setReason(reason);
        record.setMessage("Kapı " + action + " çağrısı başarılı.");
        record.setCreatedAt(Instant.now());
        tripDoorActionRepository.save(record);
    }

    /** Senkronizasyon yoksa (birim test) telafi hiç kaydedilmez; davranış bozulmaz. */
    private void registerCompensationIfActive(Trip trip, Vehicle vehicle, DoorAction action,
                                              Runnable compensationCall) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                    compensate(trip, vehicle, action, compensationCall);
                }
            }
        });
    }

    /** En iyi çaba: telafi hatası yalnız loglanır, istisna YAYILMAZ; sonucu COMPENSATION yazılır. */
    private void compensate(Trip trip, Vehicle vehicle, DoorAction failedAction, Runnable compensationCall) {
        DoorAction compensationAction = failedAction == DoorAction.UNLOCK ? DoorAction.LOCK : DoorAction.UNLOCK;
        try {
            compensationCall.run();
            doorActionRecorder.recordCompensation(trip, vehicle, compensationAction, DoorActionStatus.SUCCESS,
                    "Ana işlem geri alındı; kapı telafen " + describe(compensationAction) + ".");
            log.warn("Kapı telafisi uygulandı. tripId={}, vehicleId={}, compensationAction={}",
                    trip.getId(), vehicle.getId(), compensationAction);
        } catch (Exception ex) {
            log.error("Kapı telafi çağrısı başarısız (en iyi çaba; ana işlem sonucu değişmez). tripId={}, vehicleId={}",
                    trip.getId(), vehicle.getId(), ex);
            try {
                doorActionRecorder.recordCompensation(trip, vehicle, compensationAction, DoorActionStatus.FAILED,
                        "Telafi çağrısı başarısız: " + ex.getMessage());
            } catch (Exception recordEx) {
                log.error("Telafi denetim kaydı da yazılamadı. tripId={}", trip.getId(), recordEx);
            }
        }
    }

    private String describe(DoorAction action) {
        return action == DoorAction.LOCK ? "kilitlendi" : "açıldı";
    }
}

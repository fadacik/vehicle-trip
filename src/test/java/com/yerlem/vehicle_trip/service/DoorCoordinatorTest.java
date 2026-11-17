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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Adım 17 / A8 — DoorCoordinator birim testleri: başarı kaydı (ana transaction),
 * FAILED kaydı + 503, afterCompletion telafisi (rollback simülasyonu), commit'te
 * telafi yok, senkronizasyon yokken sakin davranış, telafi hatasının yutulması.
 */
@ExtendWith(MockitoExtension.class)
class DoorCoordinatorTest {

    @Mock
    private DoorLockService doorLockService;
    @Mock
    private TripDoorActionRepository tripDoorActionRepository;
    @Mock
    private DoorActionRecorder doorActionRecorder;

    private DoorCoordinator coordinator;
    private Trip trip;
    private Vehicle vehicle;

    @BeforeEach
    void setUp() {
        coordinator = new DoorCoordinator(doorLockService, tripDoorActionRepository, doorActionRecorder);
        trip = new Trip();
        trip.setId(10L);
        vehicle = new Vehicle();
        vehicle.setId(2L);
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("unlock başarılı: SUCCESS/UNLOCK/TRIP_START kaydı ana işleme yazılır, telafi kaydedilmez (senkronizasyon yok)")
    void unlockSuccess_recordsSuccessInMainTransaction() {
        coordinator.unlockForTripStart(trip, vehicle);

        verify(doorLockService).unlock(2L);
        ArgumentCaptor<TripDoorAction> captor = ArgumentCaptor.forClass(TripDoorAction.class);
        verify(tripDoorActionRepository).save(captor.capture());
        TripDoorAction record = captor.getValue();
        assertThat(record.getAction()).isEqualTo(DoorAction.UNLOCK);
        assertThat(record.getStatus()).isEqualTo(DoorActionStatus.SUCCESS);
        assertThat(record.getReason()).isEqualTo(DoorActionReason.TRIP_START);
        verify(doorActionRecorder, never()).recordFailed(any(), any(), any(), any(), any());
        verify(doorActionRecorder, never()).recordCompensation(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("lock başarılı: SUCCESS/LOCK/TRIP_COMPLETE kaydı yazılır")
    void lockSuccess_recordsSuccess() {
        coordinator.lockForTripCompletion(trip, vehicle);

        verify(doorLockService).lock(2L);
        ArgumentCaptor<TripDoorAction> captor = ArgumentCaptor.forClass(TripDoorAction.class);
        verify(tripDoorActionRepository).save(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo(DoorAction.LOCK);
        assertThat(captor.getValue().getStatus()).isEqualTo(DoorActionStatus.SUCCESS);
        assertThat(captor.getValue().getReason()).isEqualTo(DoorActionReason.TRIP_COMPLETE);
    }

    @Test
    @DisplayName("unlock hatası: FAILED kaydı recorder'a devredilir + 503 DOOR_UNLOCK_FAILED (Türkçe, tekrar denenebilir)")
    void unlockFailure_persistsFailedAndThrows503() {
        doThrow(new DoorServiceException("kapı servisi yok"))
                .when(doorLockService).unlock(2L);

        assertThatThrownBy(() -> coordinator.unlockForTripStart(trip, vehicle))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(apiEx.getCode()).isEqualTo("DOOR_UNLOCK_FAILED");
                    assertThat(apiEx.getMessage()).contains("tekrar deneyebilirsiniz");
                });

        verify(doorActionRecorder).recordFailed(trip, vehicle, DoorAction.UNLOCK,
                DoorActionReason.TRIP_START, "kapı servisi yok");
        verify(tripDoorActionRepository, never()).save(any());
    }

    @Test
    @DisplayName("lock hatası: FAILED kaydı + 503 DOOR_LOCK_FAILED")
    void lockFailure_persistsFailedAndThrows503() {
        doThrow(new DoorServiceException("kapı servisi yok"))
                .when(doorLockService).lock(2L);

        assertThatThrownBy(() -> coordinator.lockForTripCompletion(trip, vehicle))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(apiEx.getCode()).isEqualTo("DOOR_LOCK_FAILED");
                });

        verify(doorActionRecorder).recordFailed(trip, vehicle, DoorAction.LOCK,
                DoorActionReason.TRIP_COMPLETE, "kapı servisi yok");
    }

    @Test
    @DisplayName("rollback simülasyonu: afterCompletion(ROLLED_BACK) → ters kapı çağrısı + COMPENSATION(SUCCESS) kaydı")
    void rollbackTriggersCompensation() {
        TransactionSynchronizationManager.initSynchronization();
        coordinator.unlockForTripStart(trip, vehicle);

        synchronizations().forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(doorLockService).lock(2L);
        verify(doorActionRecorder).recordCompensation(eq(trip), eq(vehicle), eq(DoorAction.LOCK),
                eq(DoorActionStatus.SUCCESS), anyString());
    }

    @Test
    @DisplayName("commit: afterCompletion(COMMITTED) → telafi ÇAĞRILMAZ")
    void commitDoesNotCompensate() {
        TransactionSynchronizationManager.initSynchronization();
        coordinator.lockForTripCompletion(trip, vehicle);

        synchronizations().forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));

        verify(doorLockService, never()).unlock(any());
        verify(doorActionRecorder, never()).recordCompensation(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("rollback + unlock telafisi: lock başarısızsa hata YUTULUR (log), COMPENSATION(FAILED) yazılır, exception yayılmaz")
    void compensationFailure_isSwallowedAndRecorded() {
        TransactionSynchronizationManager.initSynchronization();
        coordinator.unlockForTripStart(trip, vehicle);
        doThrow(new DoorServiceException("telafi de başarısız")).when(doorLockService).lock(2L);

        assertThatCode(() -> synchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK)))
                .doesNotThrowAnyException();

        verify(doorActionRecorder).recordCompensation(eq(trip), eq(vehicle), eq(DoorAction.LOCK),
                eq(DoorActionStatus.FAILED), anyString());
    }

    @Test
    @DisplayName("senkronizasyon yok (birim test): telafi kaydı denenmez, başarı sessizce devam eder")
    void withoutSynchronizationBehavesQuietly() {
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();

        assertThatCode(() -> coordinator.unlockForTripStart(trip, vehicle)).doesNotThrowAnyException();

        verify(doorLockService).unlock(2L);
        verify(doorActionRecorder, never()).recordCompensation(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("SUCCESS audit INSERT'i basarisiz olursa: exception yayilir, telafi ONCEDEN register edilmis olur ve rollback telafiyi tetikler")
    void successAuditInsertFails_compensationAlreadyRegisteredAndTriggeredOnRollback() {
        TransactionSynchronizationManager.initSynchronization();
        // INSERT denenirken telafi senkronizasyonu ZATEN register olmus olmali (sira kaniti).
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.getSynchronizations())
                    .as("SUCCESS INSERT aninda telafi senkronizasyonu kayitli olmali").hasSize(1);
            throw new DataIntegrityViolationException(
                    "could not execute statement; constraint [uq_trip_door_one_success_per_action]");
        }).when(tripDoorActionRepository).save(any(TripDoorAction.class));

        assertThatThrownBy(() -> coordinator.unlockForTripStart(trip, vehicle))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Harici cagri yapildi, SUCCESS kaydi yazilamadi.
        verify(doorLockService).unlock(2L);
        verify(doorActionRecorder, never()).recordFailed(any(), any(), any(), any(), any());

        // Rollback: telafi gercekten calisti (ters kapi cagrisi) ve COMPENSATION kaydi yazildi.
        synchronizations().forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(doorLockService).lock(2L);
        verify(doorActionRecorder).recordCompensation(eq(trip), eq(vehicle), eq(DoorAction.LOCK),
                eq(DoorActionStatus.SUCCESS), anyString());
    }

    @Test
    @DisplayName("harici cagri basarisizsa telafi hic register edilmez (rollback'te de calismaz)")
    void externalFailure_doesNotRegisterCompensation() {
        TransactionSynchronizationManager.initSynchronization();
        doThrow(new DoorServiceException("kapı servisi yok"))
                .when(doorLockService).unlock(2L);

        assertThatThrownBy(() -> coordinator.unlockForTripStart(trip, vehicle))
                .isInstanceOf(ApiException.class);

        assertThat(TransactionSynchronizationManager.getSynchronizations())
                .as("basarisiz harici cagrida telafi kaydedilmemeli").isEmpty();

        synchronizations().forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(doorLockService, never()).lock(any());
        verify(doorActionRecorder, never()).recordCompensation(any(), any(), any(), any(), any());
    }

    private java.util.List<TransactionSynchronization> synchronizations() {
        return TransactionSynchronizationManager.getSynchronizations();
    }
}

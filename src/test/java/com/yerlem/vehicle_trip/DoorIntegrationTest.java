package com.yerlem.vehicle_trip;

import com.yerlem.vehicle_trip.dto.CompleteTripRequest;
import com.yerlem.vehicle_trip.dto.TripSummaryResponse;
import com.yerlem.vehicle_trip.exception.ApiException;
import com.yerlem.vehicle_trip.integration.MockDoorLockService;
import com.yerlem.vehicle_trip.service.TripCompletionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Adım 17 / A8 — Kapı entegrasyon testleri (gerçek PostgreSQL + gerçek transaction).
 *
 * Hata enjeksiyonu gerçek MockDoorLockService sayaç API'siyle yapılır (konteynerle
 * aynı JVM'deki tek örnek). Doğrulananlar: start'ta unlock hatası → 503 + tam rollback
 * + KALICI FAILED izi + tekrar denemede tek SUCCESS; complete'ta lock hatası → 503 +
 * tahsilat/konum dahil tam rollback + tekrar denemede tek TRIP_CHARGE ve tek SUCCESS
 * LOCK; eşzamanlı iki complete → tek kapı çağrısı; kısmi unique index değişmezi;
 * ana işlem rollback'inde telafi (COMPENSATION) REQUIRES_NEW ile kalıcı.
 *
 * Testler arası sızıntı olmaması için mock sayaçları HER testten önce ve sonra
 * sıfırlanır (Spring context sınıf ARALIĞINDA paylaşıılır).
 */
class DoorIntegrationTest extends AbstractIntegrationTest {

    private static final double KADIKOY_LAT = 40.9919, KADIKOY_LNG = 29.0270;
    private static final BigDecimal BALANCE = new BigDecimal("1000.00");
    private static final long TIMEOUT_SECONDS = 30;

    @Autowired
    private MockDoorLockService doorLockService;
    @Autowired
    private TripCompletionService tripCompletionService;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    @AfterEach
    void resetMockDoor() {
        doorLockService.reset();
    }

    // 1) UNLOCK HATASI: start 503; trip CREATED + araç AVAILABLE kalır, FAILED izi kalıcı,
    //    başarılı TRIP_START kaydı YOK; sayaç sıfırlanınca tekrar start başarılı ve tam 1 SUCCESS.
    @Test
    @DisplayName("unlock hatası -> start 503; DB: trip CREATED, arac AVAILABLE, FAILED kalici, SUCCESS 0; retry -> ACTIVE ve tam 1 SUCCESS UNLOCK")
    void unlockFailure_rollsBackStart_butKeepsFailedTrace_retrySucceedsOnce() {
        Long userId = createVerifiedUser(BALANCE);
        Long vehicleId = createVehicle();
        Long tripId = createCreatedTrip(userId, vehicleId, KADIKOY_LAT, KADIKOY_LNG).id();

        doorLockService.setFailureCounters(1, 0);
        assertThatThrownBy(() -> tripService.startTrip(tripId))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(((ApiException) ex).getCode()).isEqualTo("DOOR_UNLOCK_FAILED");
                });

        assertThat(tripStatus(tripId)).as("trip CREATED kalmali (rollback)").isEqualTo("CREATED");
        assertThat(vehicleStatus(vehicleId)).as("arac AVAILABLE kalmali (rollback)").isEqualTo("AVAILABLE");
        assertThat(doorActionCount(tripId, "FAILED", null)).as("FAILED izi kalici").isEqualTo(1L);
        assertThat(doorActionCount(tripId, "SUCCESS", "TRIP_START")).as("basarili TRIP_START kaydi yok").isZero();

        doorLockService.reset();
        tripService.startTrip(tripId);

        assertThat(tripStatus(tripId)).isEqualTo("ACTIVE");
        assertThat(doorActionCount(tripId, "SUCCESS", "TRIP_START"))
                .as("retry sonrasi TAM 1 basarili UNLOCK").isEqualTo(1L);
    }

    // 2) LOCK HATASI: complete 503; trip ACTIVE, TRIP_CHARGE yok, bakiye değişmedi, araç
    //    IN_USE, son konum yok; tekrar complete → tam tek tahsilat + tam 1 SUCCESS LOCK.
    @Test
    @DisplayName("lock hatası -> complete 503; DB: trip ACTIVE, TRIP_CHARGE 0, bakiye ayni, arac IN_USE, konum yok, FAILED var; retry -> COMPLETED, TRIP_CHARGE 1, SUCCESS LOCK 1")
    void lockFailure_rollsBackCompletionEntirely_retryCompletesWithSingleCharge() {
        Long userId = createVerifiedUser(BALANCE);
        Long vehicleId = createVehicle();
        Long tripId = createCreatedTrip(userId, vehicleId, KADIKOY_LAT, KADIKOY_LNG).id();
        tripService.startTrip(tripId);
        BigDecimal balanceBefore = balanceOf(userId);

        doorLockService.setFailureCounters(0, 1);
        CompleteTripRequest request = new CompleteTripRequest(KADIKOY_LAT, KADIKOY_LNG, null, null);
        assertThatThrownBy(() -> tripCompletionService.completeTrip(tripId, request))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(((ApiException) ex).getCode()).isEqualTo("DOOR_LOCK_FAILED");
                });

        assertThat(tripStatus(tripId)).as("trip ACTIVE kalmali").isEqualTo("ACTIVE");
        assertThat(chargeCount(tripId)).as("TRIP_CHARGE yok").isZero();
        assertThat(balanceOf(userId)).as("bakiye degismedi").isEqualByComparingTo(balanceBefore);
        assertThat(vehicleStatus(vehicleId)).as("arac IN_USE kalmali").isEqualTo("IN_USE");
        assertThat(count("SELECT COUNT(*) FROM trip_locations WHERE trip_id = ?", tripId))
                .as("son konum satiri yok").isZero();
        assertThat(doorActionCount(tripId, "FAILED", null)).as("FAILED LOCK izi kalici").isEqualTo(1L);

        doorLockService.reset();
        TripSummaryResponse summary = tripCompletionService.completeTrip(tripId, request);

        assertThat(summary.status().name()).as("retry COMPLETED").isEqualTo("COMPLETED");
        assertThat(chargeCount(tripId)).as("retry sonrasi TAM 1 TRIP_CHARGE").isEqualTo(1L);
        assertThat(doorActionCount(tripId, "SUCCESS", "TRIP_COMPLETE"))
                .as("TAM 1 basarili LOCK").isEqualTo(1L);
        assertThat(balanceOf(userId)).as("bakiye = onceki - ucret")
                .isEqualByComparingTo(balanceBefore.subtract(estimatedFeeOf(tripId)));
    }

    // 3) EŞZAMANLI İKİ COMPLETE (kapı başarılı): tek tahsilat + TEK SUCCESS LOCK
    //    (idempotent kaybeden kapıya dokunmaz).
    @Test
    @DisplayName("es zamanli iki complete -> ikisi de doner; TRIP_CHARGE 1; SUCCESS LOCK tam 1 (idempotent yol kapıya dokunmaz)")
    void concurrentComplete_singleChargeAndSingleSuccessLock() throws Exception {
        Long userId = createVerifiedUser(BALANCE);
        Long vehicleId = createVehicle();
        Long tripId = createCreatedTrip(userId, vehicleId, KADIKOY_LAT, KADIKOY_LNG).id();
        tripService.startTrip(tripId);

        CompleteTripRequest request = new CompleteTripRequest(KADIKOY_LAT, KADIKOY_LNG, null, null);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // CyclicBarrier ile iki complete GERÇEKTEN aynı anda başlar (biri diğerini
            // beklemeden trip kilidine yarışır; kazanan kapıyı kilitler, kaybeden
            // idempotent yolda kapıya dokunmaz).
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<Throwable> a = pool.submit(() -> {
                barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return attempt(() -> tripCompletionService.completeTrip(tripId, request));
            });
            Future<Throwable> b = pool.submit(() -> {
                barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return attempt(() -> tripCompletionService.completeTrip(tripId, request));
            });
            assertThat(a.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("A hatasiz").isNull();
            assertThat(b.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("B hatasiz").isNull();
        } finally {
            pool.shutdownNow();
        }

        assertThat(chargeCount(tripId)).as("tek TRIP_CHARGE").isEqualTo(1L);
        assertThat(doorActionCount(tripId, "SUCCESS", "TRIP_COMPLETE"))
                .as("TAM 1 basarili LOCK").isEqualTo(1L);
        assertThat(count("SELECT COUNT(*) FROM trip_fees WHERE trip_id = ? AND fee_type = 'DISTANCE'", tripId))
                .as("tek DISTANCE fee").isEqualTo(1L);
    }

    // 4) DB DEĞİŞMEZİ: aynı trip+action için ikinci SUCCESS kaydı kısmi unique index ile reddedilir.
    @Test
    @DisplayName("ikinci SUCCESS UNLOCK insasi -> mesajda uq_trip_door_one_success_per_action")
    void secondSuccessUnlockIsRejectedByPartialUniqueIndex() {
        Long userId = createVerifiedUser(BALANCE);
        Long vehicleId = createVehicle();
        Long tripId = createCreatedTrip(userId, vehicleId, KADIKOY_LAT, KADIKOY_LNG).id();
        tripService.startTrip(tripId);
        assertThat(doorActionCount(tripId, "SUCCESS", "TRIP_START")).isEqualTo(1L);

        Throwable failure = null;
        try {
            jdbcTemplate.update("INSERT INTO trip_door_actions (trip_id, vehicle_id, action, status, reason, message) "
                    + "VALUES (?, ?, 'UNLOCK', 'SUCCESS', 'TRIP_START', 'ikinci deneme')", tripId, vehicleId);
        } catch (DataAccessException ex) {
            failure = ex;
        }

        assertThat(failure).as("ikinci SUCCESS UNLOCK reddedilmeli").isNotNull();
        assertThat(messagesOf(failure)).contains("uq_trip_door_one_success_per_action");
        assertThat(doorActionCount(tripId, "SUCCESS", "TRIP_START")).as("tek SUCCESS kalmali").isEqualTo(1L);
    }

    // 5) TELAFİ: unlock BAŞARILI olduktan sonra ana işlem geri alınırsa (programatik
    //    rollback) kapı tersine çalıştırılır ve COMPENSATION kaydı REQUIRES_NEW ile kalır.
    @Test
    @DisplayName("unlock sonrasi ana rollback -> trip CREATED/araç AVAILABLE'a doner, SUCCESS izi kalkar, COMPENSATION(LOCK/SUCCESS) kalici")
    void rollbackAfterSuccessfulUnlock_triggersCompensationTrace() {
        Long userId = createVerifiedUser(BALANCE);
        Long vehicleId = createVehicle();
        Long tripId = createCreatedTrip(userId, vehicleId, KADIKOY_LAT, KADIKOY_LNG).id();

        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.executeWithoutResult(status -> {
            tripService.startTrip(tripId);
            status.setRollbackOnly();
        });

        assertThat(tripStatus(tripId)).as("rollback: trip CREATED").isEqualTo("CREATED");
        assertThat(vehicleStatus(vehicleId)).as("rollback: arac AVAILABLE").isEqualTo("AVAILABLE");
        assertThat(doorActionCount(tripId, "SUCCESS", "TRIP_START"))
                .as("ana transaction iziyle SUCCESS de geri alindi").isZero();
        assertThat(count("SELECT COUNT(*) FROM trip_door_actions WHERE trip_id = ? AND reason = 'COMPENSATION' "
                + "AND action = 'LOCK' AND status = 'SUCCESS'", tripId))
                .as("telafi kaydi REQUIRES_NEW ile kalici").isEqualTo(1L);
        assertThatCode(() -> doorLockService.unlock(vehicleId)).as("kapı telafen kilitlendi, tekrar açilabilir")
                .doesNotThrowAnyException();
    }

    // 6) SUCCESS DENETİM INSERT'İ BAŞARISIZ → ROLLBACK TELAFİYİ TETİKLER:
    //    Önceden eklenmiş başarılı UNLOCK kaydı, coordinator'in kendi SUCCESS INSERT'ini
    //    uq_trip_door_one_success_per_action ile ihlal ettirir. Harici kapı çağrısı BAŞARILI
    //    olduğu için telafi register edilmiş olmalı; transaction rollback olunca telafi
    //    çalışır ve sonucu REQUIRES_NEW ile COMPENSATION olarak kalıcı yazılır.
    @Test
    @DisplayName("SUCCESS INSERT'i kısmi unique ile basarisiz -> rollback telafiyi tetikler: COMPENSATION(LOCK/SUCCESS) kalici; trip CREATED, arac AVAILABLE")
    void successAuditInsertFails_rollbackTriggersCompensationTrace() {
        Long userId = createVerifiedUser(BALANCE);
        Long vehicleId = createVehicle();
        Long tripId = createCreatedTrip(userId, vehicleId, KADIKOY_LAT, KADIKOY_LNG).id();

        // Ayni (trip, action, SUCCESS, TRIP_START) kaydı daha önce yazıldı: coordinator'in
        // SUCCESS INSERT'i artık kısmi unique index ile reddedilecek.
        jdbcTemplate.update("INSERT INTO trip_door_actions (trip_id, vehicle_id, action, status, reason, message) "
                + "VALUES (?, ?, 'UNLOCK', 'SUCCESS', 'TRIP_START', 'onceki basarili kayit')", tripId, vehicleId);

        assertThatThrownBy(() -> tripService.startTrip(tripId))
                .isInstanceOf(DataAccessException.class);

        assertThat(count("SELECT COUNT(*) FROM trip_door_actions WHERE trip_id = ? AND reason = 'COMPENSATION' "
                + "AND action = 'LOCK' AND status = 'SUCCESS'", tripId))
                .as("rollback telafiyi tetikledi; COMPENSATION kaydi REQUIRES_NEW ile kalici").isEqualTo(1L);
        assertThat(tripStatus(tripId)).as("rollback: trip CREATED").isEqualTo("CREATED");
        assertThat(vehicleStatus(vehicleId)).as("rollback: arac AVAILABLE").isEqualTo("AVAILABLE");
        assertThat(count("SELECT COUNT(*) FROM trip_door_actions WHERE trip_id = ? AND status = 'FAILED'", tripId))
                .as("harici cagri basariliydi: FAILED kaydi yok").isZero();
    }

    /* =============================== yardımcılar =============================== */

    private Throwable attempt(Runnable action) {
        try {
            action.run();
            return null;
        } catch (Throwable ex) {
            return ex;
        }
    }

    private long doorActionCount(long tripId, String status, String reason) {
        return reason == null
                ? count("SELECT COUNT(*) FROM trip_door_actions WHERE trip_id = ? AND status = ?", tripId, status)
                : count("SELECT COUNT(*) FROM trip_door_actions WHERE trip_id = ? AND status = ? AND reason = ?",
                        tripId, status, reason);
    }

    private long chargeCount(long tripId) {
        return count("SELECT COUNT(*) FROM wallet_transactions WHERE trip_id = ? AND type = 'TRIP_CHARGE'", tripId);
    }

    private long count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Long.class, args);
    }

    private String tripStatus(long tripId) {
        return jdbcTemplate.queryForObject("SELECT status FROM trips WHERE id = ?", String.class, tripId);
    }

    private String vehicleStatus(long vehicleId) {
        return jdbcTemplate.queryForObject("SELECT status FROM vehicles WHERE id = ?", String.class, vehicleId);
    }

    private BigDecimal balanceOf(long userId) {
        return jdbcTemplate.queryForObject("SELECT balance FROM wallets WHERE user_id = ?", BigDecimal.class, userId);
    }

    private BigDecimal estimatedFeeOf(long tripId) {
        return jdbcTemplate.queryForObject("SELECT estimated_fee FROM trips WHERE id = ?", BigDecimal.class, tripId);
    }
}

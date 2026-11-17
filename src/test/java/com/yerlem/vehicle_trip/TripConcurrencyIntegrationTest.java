package com.yerlem.vehicle_trip;

import com.yerlem.vehicle_trip.dto.CompleteTripRequest;
import com.yerlem.vehicle_trip.dto.FaultReportRequest;
import com.yerlem.vehicle_trip.dto.LocationRecordRequest;
import com.yerlem.vehicle_trip.dto.TripLocationResponse;
import com.yerlem.vehicle_trip.dto.TripSummaryResponse;
import com.yerlem.vehicle_trip.enums.TripStatus;
import com.yerlem.vehicle_trip.exception.ApiException;
import com.yerlem.vehicle_trip.service.FaultService;
import com.yerlem.vehicle_trip.service.TripCompletionService;
import com.yerlem.vehicle_trip.service.TripLocationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Adim 14 / Parca 2 — Es zamanlilik ve geri alma (gercek PostgreSQL 16 kilitleri).
 *
 * Kismi unique index'ler ve PESSIMISTIC_WRITE (FOR UPDATE) kilitleri yalnizca gercek
 * PostgreSQL'de anlam tasir (H2'de kismi unique index yok). Bu testler start/complete/
 * fault/recordLocation'i gercek iki JDBC baglantisiyla AYNI anda (CyclicBarrier ile senkron)
 * calistirip sonuc SAYILARINI dogrular: tam 1 basarili + beklenen 409'lar; es zamanli
 * tahsilatta tek TRIP_CHARGE; tahsilat basarisizliginda tam rollback (trip ACTIVE kalir,
 * son konum/fee/charge yazilmaz); ariza + tamamlamada MAINTENANCE korunur. Kilit sirasi
 * trip -> arac -> cuzdan oldugu icin complete ile fault ayni trip kilidinde siraya girer
 * (deadlock yok).
 *
 * Sinif duzeyinde @Transactional YOK (cok is parcali testler gercek transaction/lock ister).
 * Her senaryo 5 kez, HER TEKRARDA YENI veriyle (benzersiz telefon/plaka) kosar.
 */
class TripConcurrencyIntegrationTest extends AbstractIntegrationTest {

    private static final int REPEATS = 5;
    private static final long TIMEOUT_SECONDS = 30;

    private static final double KADIKOY_LAT = 40.9919, KADIKOY_LNG = 29.0270;
    private static final double PENDIK_LAT = 40.8780, PENDIK_LNG = 29.2330;

    private static final BigDecimal BALANCE = new BigDecimal("1000.00");
    private static final BigDecimal RANGE_AT_START = new BigDecimal("280.00");

    @Autowired
    private TripCompletionService tripCompletionService;
    @Autowired
    private FaultService faultService;
    @Autowired
    private TripLocationService tripLocationService;

    // 1) AYNI ARAC, iki farkli kullanici, iki CREATED trip -> es zamanli start: tam 1 basarili
    @Test
    @DisplayName("Ayni arac, iki kullanici, es zamanli start -> tam 1 basarili; digeri 409 VEHICLE_NOT_AVAILABLE; arac IN_USE; 1 ACTIVE trip")
    void sameVehicle_twoUsers_concurrentStart_oneWins() {
        for (int repeat = 0; repeat < REPEATS; repeat++) {
            Long user1 = createVerifiedUser(BALANCE);
            Long user2 = createVerifiedUser(BALANCE);
            Long vehicleId = createVehicle();
            Long trip1 = createCreatedTrip(user1, vehicleId, KADIKOY_LAT, KADIKOY_LNG).id();
            Long trip2 = createCreatedTrip(user2, vehicleId, KADIKOY_LAT, KADIKOY_LNG).id();

            Outcome[] r = raceTwo(() -> tripService.startTrip(trip1), () -> tripService.startTrip(trip2));
            String tag = "tekrar=" + repeat;

            assertThat(succeededCount(r)).as(tag + " -> tam 1 basarili olmali").isEqualTo(1L);
            Outcome loser = r[0].succeeded() ? r[1] : r[0];
            assertThat(hasError(loser, HttpStatus.CONFLICT, "VEHICLE_NOT_AVAILABLE"))
                    .as(tag + " -> kaybeden 409 VEHICLE_NOT_AVAILABLE: " + errorText(loser)).isTrue();
            assertThat(vehicleStatus(vehicleId)).as(tag).isEqualTo("IN_USE");
            assertThat(count("SELECT COUNT(*) FROM trips WHERE vehicle_id = ? AND status = 'ACTIVE'", vehicleId))
                    .as(tag + " -> arac icin 1 ACTIVE trip").isEqualTo(1L);
        }
    }

    // 2) AYNI KULLANICI, iki farkli arac, iki CREATED trip -> es zamanli start: tam 1 basarili, DATA_CONFLICT sizmaz
    @Test
    @DisplayName("Ayni kullanici, iki arac, es zamanli start -> tam 1 basarili; digeri 409 USER_HAS_ACTIVE_TRIP (DATA_CONFLICT sizmaz); 1 ACTIVE trip")
    void sameUser_twoVehicles_concurrentStart_oneWins() {
        for (int repeat = 0; repeat < REPEATS; repeat++) {
            Long userId = createVerifiedUser(BALANCE);
            Long vehicle1 = createVehicle();
            Long vehicle2 = createVehicle();
            Long trip1 = createCreatedTrip(userId, vehicle1, KADIKOY_LAT, KADIKOY_LNG).id();
            Long trip2 = createCreatedTrip(userId, vehicle2, KADIKOY_LAT, KADIKOY_LNG).id();

            Outcome[] r = raceTwo(() -> tripService.startTrip(trip1), () -> tripService.startTrip(trip2));
            String tag = "tekrar=" + repeat;

            assertThat(succeededCount(r)).as(tag).isEqualTo(1L);
            Outcome loser = r[0].succeeded() ? r[1] : r[0];
            assertThat(hasError(loser, HttpStatus.CONFLICT, "USER_HAS_ACTIVE_TRIP"))
                    .as(tag + " -> kaybeden 409 USER_HAS_ACTIVE_TRIP: " + errorText(loser)).isTrue();
            assertThat(messagesOf(loser.error)).as(tag + " -> DATA_CONFLICT sizmamali")
                    .doesNotContain("data_conflict");
            assertThat(count("SELECT COUNT(*) FROM trips WHERE user_id = ? AND status = 'ACTIVE'", userId))
                    .as(tag + " -> kullanici icin 1 ACTIVE trip").isEqualTo(1L);
        }
    }

    // 3) AYNI TRIP iki kez start -> tam 1 basarili; digeri 409 (beklenen kodlardan biri).
    // Kontrol sirasi geregi kaybeden kod zamana gore degisir ve hepsi GECERLIDIR:
    // start'ta kullanici aktif-trip kontrolu (existsByUserIdAndStatus), arac durumu kontrolunden ONCE gelir
    // ve her iki thread de trip'i kilitsiz okuyup CREATED gordugu icin status kontrolunu gecer.
    // Iki istekten ikincisi (kaybeden), kazananin AYNI trip'ini ACTIVE gorebilir -> USER_HAS_ACTIVE_TRIP;
    // bazi zamanlamalarda arac IN_USE oldugu icin once VEHICLE_NOT_AVAILABLE, ya da status kontrolu
    // kilitten sonraya kalirsa INVALID_TRIP_STATE doner. (Uretim dogru; DB'de uq_trips_one_active_per_user yedek.)
    @Test
    @DisplayName("Ayni trip iki kez es zamanli start -> tam 1 basarili; digeri 409 (USER_HAS_ACTIVE_TRIP / VEHICLE_NOT_AVAILABLE / INVALID_TRIP_STATE / CONCURRENT_MODIFICATION)")
    void sameTrip_concurrentStart_oneWins() {
        for (int repeat = 0; repeat < REPEATS; repeat++) {
            Long userId = createVerifiedUser(BALANCE);
            Long vehicleId = createVehicle();
            Long tripId = createCreatedTrip(userId, vehicleId, KADIKOY_LAT, KADIKOY_LNG).id();

            Outcome[] r = raceTwo(() -> tripService.startTrip(tripId), () -> tripService.startTrip(tripId));
            String tag = "tekrar=" + repeat;

            assertThat(succeededCount(r)).as(tag + " -> tam 1 basarili olmali").isEqualTo(1L);
            Outcome loser = r[0].succeeded() ? r[1] : r[0];
            assertThat(loser.error).as(tag + " -> kaybeden hata vermeli").isInstanceOf(ApiException.class);
            ApiException loserEx = (ApiException) loser.error;
            assertThat(loserEx.getStatus()).as(tag + " -> kaybeden HTTP 409 CONFLICT: " + errorText(loser))
                    .isEqualTo(HttpStatus.CONFLICT);
            assertThat(loserEx.getCode())
                    .as(tag + " -> is kurali 409'u; DATA_CONFLICT/INTERNAL_ERROR SIZMAMALI: " + errorText(loser))
                    .isNotIn("DATA_CONFLICT", "INTERNAL_ERROR");
            assertThat(hasErrorCode(loser, "VEHICLE_NOT_AVAILABLE", "INVALID_TRIP_STATE", "CONCURRENT_MODIFICATION", "USER_HAS_ACTIVE_TRIP"))
                    .as(tag + " -> beklenen 409 kodlarindan biri: " + errorText(loser)).isTrue();
            assertThat(count("SELECT COUNT(*) FROM trips WHERE id = ? AND status = 'ACTIVE'", tripId))
                    .as(tag + " -> bu trip icin tam 1 ACTIVE").isEqualTo(1L);
            assertThat(vehicleStatus(vehicleId)).as(tag + " -> arac IN_USE").isEqualTo("IN_USE");
        }
    }

    // 4) AYNI TRIP, iki kez complete (son konum = hedef) -> iKISI DE COMPLETED; tek TRIP_CHARGE; tek CHARGED fee
    @Test
    @DisplayName("Ayni trip es zamanli complete (hedefe) -> ikisi de COMPLETED ozet; TRIP_CHARGE tam 1; fee tek+CHARGED; bakiye start-ucret; arac AVAILABLE")
    void sameTrip_concurrentComplete_singleCharge() {
        for (int repeat = 0; repeat < REPEATS; repeat++) {
            Long userId = createVerifiedUser(BALANCE);
            Long vehicleId = createVehicle();
            Long tripId = createCreatedTrip(userId, vehicleId, KADIKOY_LAT, KADIKOY_LNG).id();
            tripService.startTrip(tripId);
            BigDecimal fee = estimatedFeeOf(tripId);
            BigDecimal balanceBefore = balanceOf(userId);

            CompleteTripRequest req = new CompleteTripRequest(KADIKOY_LAT, KADIKOY_LNG, null, null);
            Outcome[] r = raceTwo(
                    () -> tripCompletionService.completeTrip(tripId, req),
                    () -> tripCompletionService.completeTrip(tripId, req));
            String tag = "tekrar=" + repeat;

            assertThat(succeededCount(r))
                    .as(tag + " -> iki complete de donmeli: " + errorText(r[0]) + " | " + errorText(r[1]))
                    .isEqualTo(2L);
            for (Outcome o : r) {
                assertThat(((TripSummaryResponse) o.value).status())
                        .as(tag + " -> tamamlanmis ozet").isEqualTo(TripStatus.COMPLETED);
            }
            assertThat(count("SELECT COUNT(*) FROM wallet_transactions WHERE trip_id = ? AND type = 'TRIP_CHARGE'", tripId))
                    .as(tag + " -> tek TRIP_CHARGE").isEqualTo(1L);
            assertThat(balanceOf(userId)).as(tag + " -> bakiye = start - ucret")
                    .isEqualByComparingTo(balanceBefore.subtract(fee));
            assertThat(count("SELECT COUNT(*) FROM trip_fees WHERE trip_id = ?", tripId))
                    .as(tag + " -> tek fee satiri").isEqualTo(1L);
            assertThat(feeStatus(tripId)).as(tag + " -> fee CHARGED").isEqualTo("CHARGED");
            assertThat(vehicleStatus(vehicleId)).as(tag + " -> arac AVAILABLE").isEqualTo("AVAILABLE");
        }
    }

    // 5) AYNI recordedAt ile iki konum -> tek satir; biri duplicate=true; SPEED_LIMIT uyarisi tam 1
    @Test
    @DisplayName("Ayni recordedAt iki es zamanli konum -> trip_locations'ta tam 1 satir; biri duplicate=true; SPEED_LIMIT uyarisi tam 1")
    void sameRecordedAt_concurrentLocation_singleRowOneDuplicate() {
        for (int repeat = 0; repeat < REPEATS; repeat++) {
            Long userId = createVerifiedUser(BALANCE);
            Long vehicleId = createVehicle();
            Long tripId = createCreatedTrip(userId, vehicleId, KADIKOY_LAT, KADIKOY_LNG).id();
            tripService.startTrip(tripId);
            Instant recordedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

            LocationRecordRequest req = new LocationRecordRequest(TAKSIM_LAT, TAKSIM_LNG, new BigDecimal("85"), recordedAt);
            Outcome[] r = raceTwo(
                    () -> tripLocationService.recordLocation(tripId, req),
                    () -> tripLocationService.recordLocation(tripId, req));
            String tag = "tekrar=" + repeat;

            assertThat(r[0].succeeded()).as(tag + " -> A hata vermemeli: " + errorText(r[0])).isTrue();
            assertThat(r[1].succeeded()).as(tag + " -> B hata vermemeli: " + errorText(r[1])).isTrue();
            boolean dupA = ((TripLocationResponse) r[0].value).duplicate();
            boolean dupB = ((TripLocationResponse) r[1].value).duplicate();
            assertThat(dupA ^ dupB).as(tag + " -> tam olarak biri duplicate=true").isTrue();
            assertThat(count("SELECT COUNT(*) FROM trip_locations WHERE trip_id = ?", tripId))
                    .as(tag + " -> tek konum satiri").isEqualTo(1L);
            assertThat(count("SELECT COUNT(*) FROM trip_warnings WHERE trip_id = ? AND warning_type = 'SPEED_LIMIT'", tripId))
                    .as(tag + " -> tek SPEED_LIMIT uyarisi").isEqualTo(1L);
        }
    }

    // 6) TAHSILAT BASARISIZ -> tam rollback; sonra bakiye yukle -> COMPLETED
    @Test
    @DisplayName("Bakiye ucretin altina cekilip complete -> 402 PAYMENT_FAILED + tam rollback; bakiye yukleyince tekrar complete -> COMPLETED, TRIP_CHARGE 1")
    void paymentFailure_rollsBack_thenRetryCompletes() {
        for (int repeat = 0; repeat < REPEATS; repeat++) {
            Long userId = createVerifiedUser(BALANCE);
            Long vehicleId = createVehicle();
            Long tripId = createCreatedTrip(userId, vehicleId, KADIKOY_LAT, KADIKOY_LNG).id();
            tripService.startTrip(tripId);
            BigDecimal fee = estimatedFeeOf(tripId);
            jdbcTemplate.update("UPDATE wallets SET balance = ? WHERE user_id = ?", fee.subtract(BigDecimal.ONE), userId);
            String tag = "tekrar=" + repeat;

            CompleteTripRequest req = new CompleteTripRequest(KADIKOY_LAT, KADIKOY_LNG, null, null);
            assertThatThrownBy(() -> tripCompletionService.completeTrip(tripId, req))
                    .as(tag).isInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("PAYMENT_FAILED"));

            assertThat(tripStatus(tripId)).as(tag + " -> trip ACTIVE kalmali").isEqualTo("ACTIVE");
            assertThat(completedAtIsNull(tripId)).as(tag + " -> completedAt null").isTrue();
            assertThat(count("SELECT COUNT(*) FROM wallet_transactions WHERE trip_id = ? AND type = 'TRIP_CHARGE'", tripId))
                    .as(tag + " -> TRIP_CHARGE yok").isEqualTo(0L);
            assertThat(count("SELECT COUNT(*) FROM trip_locations WHERE trip_id = ?", tripId))
                    .as(tag + " -> son konum satiri yok (rollback)").isEqualTo(0L);
            assertThat(count("SELECT COUNT(*) FROM trip_fees WHERE trip_id = ? AND status = 'CHARGED'", tripId))
                    .as(tag + " -> CHARGED fee yok").isEqualTo(0L);
            assertThat(count("SELECT COUNT(*) FROM trip_fees WHERE trip_id = ?", tripId))
                    .as(tag + " -> rollback sonrasi trip_fees satiri yok (PENDING/CHARGED hicbiri)")
                    .isEqualTo(0L);
            assertThat(vehicleStatus(vehicleId)).as(tag + " -> arac IN_USE").isEqualTo("IN_USE");
            assertThat(vehicleLatitude(vehicleId)).as(tag + " -> arac konumu degismemis").isCloseTo(TAKSIM_LAT, within(1e-6));
            assertThat(vehicleRemaining(vehicleId)).as(tag + " -> menzil degismemis").isEqualByComparingTo(RANGE_AT_START);

            jdbcTemplate.update("UPDATE wallets SET balance = ? WHERE user_id = ?", new BigDecimal("2000.00"), userId);
            TripSummaryResponse summary = tripCompletionService.completeTrip(tripId, req);
            assertThat(summary.status()).as(tag + " -> retry COMPLETED").isEqualTo(TripStatus.COMPLETED);
            assertThat(count("SELECT COUNT(*) FROM wallet_transactions WHERE trip_id = ? AND type = 'TRIP_CHARGE'", tripId))
                    .as(tag + " -> retry sonrasi tek TRIP_CHARGE").isEqualTo(1L);
        }
    }

    // 7) ARIZA + TAMAMLAMA -> MAINTENANCE korunur, konum/menzil guncellenir, trip COMPLETED
    @Test
    @DisplayName("Ariza ENGINE -> arac MAINTENANCE; hedeften uzak noktada complete (ariza serbest) -> COMPLETED; arac MAINTENANCE KALIR; konum PENDIK, menzil duser")
    void faultThenComplete_maintenanceStays() {
        for (int repeat = 0; repeat < REPEATS; repeat++) {
            Long userId = createVerifiedUser(BALANCE);
            Long vehicleId = createVehicle();
            Long tripId = createCreatedTrip(userId, vehicleId, KADIKOY_LAT, KADIKOY_LNG).id();
            tripService.startTrip(tripId);
            faultService.reportFault(tripId, new FaultReportRequest("ENGINE", "test ariza"));
            String tag = "tekrar=" + repeat;
            assertThat(vehicleStatus(vehicleId)).as(tag + " -> ariza sonrasi MAINTENANCE").isEqualTo("MAINTENANCE");

            TripSummaryResponse summary = tripCompletionService.completeTrip(tripId,
                    new CompleteTripRequest(PENDIK_LAT, PENDIK_LNG, null, null));

            assertThat(summary.status()).as(tag + " -> COMPLETED").isEqualTo(TripStatus.COMPLETED);
            assertThat(vehicleStatus(vehicleId)).as(tag + " -> MAINTENANCE KALIR").isEqualTo("MAINTENANCE");
            assertThat(vehicleLatitude(vehicleId)).as(tag + " -> konum PENDIK lat").isCloseTo(PENDIK_LAT, within(1e-6));
            assertThat(vehicleLongitude(vehicleId)).as(tag + " -> konum PENDIK lng").isCloseTo(PENDIK_LNG, within(1e-6));
            assertThat(vehicleRemaining(vehicleId)).as(tag + " -> menzil duser").isLessThan(RANGE_AT_START);
        }
    }

    // 8) KILIT SIRASI KANITI: es zamanli complete + fault deadlock/timeout uretmez
    @Test
    @DisplayName("Ayni trip es zamanli complete(hedef) + fault -> deadlock/timeout YOK (<=30s); trip COMPLETED; TRIP_CHARGE 1; arac MAINTENANCE veya AVAILABLE")
    void concurrentCompleteAndFault_noDeadlock() {
        for (int repeat = 0; repeat < REPEATS; repeat++) {
            Long userId = createVerifiedUser(BALANCE);
            Long vehicleId = createVehicle();
            Long tripId = createCreatedTrip(userId, vehicleId, KADIKOY_LAT, KADIKOY_LNG).id();
            tripService.startTrip(tripId);
            String tag = "tekrar=" + repeat;

            Outcome[] r = raceTwo(
                    () -> tripCompletionService.completeTrip(tripId, new CompleteTripRequest(KADIKOY_LAT, KADIKOY_LNG, null, null)),
                    () -> faultService.reportFault(tripId, new FaultReportRequest("ENGINE", "yaris")));
            Outcome complete = r[0];
            Outcome fault = r[1];

            assertThat(complete.succeeded())
                    .as(tag + " -> complete 30sn icinde bitmeli (deadlock yok): " + errorText(complete)).isTrue();
            assertThat(fault.succeeded() || hasError(fault, HttpStatus.CONFLICT, "INVALID_TRIP_STATE"))
                    .as(tag + " -> fault beklenen sonuclardan biri (deadlock/timeout degil): " + errorText(fault)).isTrue();

            assertThat(tripStatus(tripId)).as(tag + " -> trip COMPLETED").isEqualTo("COMPLETED");
            assertThat(count("SELECT COUNT(*) FROM wallet_transactions WHERE trip_id = ? AND type = 'TRIP_CHARGE'", tripId))
                    .as(tag + " -> tek TRIP_CHARGE").isEqualTo(1L);
            assertThat(vehicleStatus(vehicleId))
                    .as(tag + " -> arac MAINTENANCE (fault once) veya AVAILABLE (complete once)")
                    .isIn("MAINTENANCE", "AVAILABLE");
        }
    }

    /* =========================== es zamanlilik kosucusu =========================== */

    /** Iki cagiyi CyclicBarrier ile ayni anda baslatir; sonuclari (deger ya da hata) doner. */
    private Outcome[] raceTwo(Callable<Object> taskA, Callable<Object> taskB) {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<Object> futureA = pool.submit(() -> {
                barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return taskA.call();
            });
            Future<Object> futureB = pool.submit(() -> {
                barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return taskB.call();
            });
            return new Outcome[]{capture(futureA), capture(futureB)};
        } finally {
            pool.shutdownNow();
        }
    }

    private Outcome capture(Future<Object> future) {
        try {
            return new Outcome(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS), null);
        } catch (ExecutionException e) {
            return new Outcome(null, e.getCause());
        } catch (TimeoutException | InterruptedException | CancellationException e) {
            return new Outcome(null, e);
        }
    }

    private static long succeededCount(Outcome... outcomes) {
        return Arrays.stream(outcomes).filter(Outcome::succeeded).count();
    }

    private static boolean hasError(Outcome outcome, HttpStatus status, String code) {
        return outcome.error instanceof ApiException ex
                && ex.getStatus() == status
                && ex.getCode().equals(code);
    }

    private static boolean hasErrorCode(Outcome outcome, String... codes) {
        return outcome.error instanceof ApiException ex
                && Arrays.asList(codes).contains(ex.getCode());
    }

    private static String errorText(Outcome outcome) {
        return outcome.error == null ? "OK" : outcome.error.getClass().getSimpleName() + ": " + outcome.error.getMessage();
    }

    /* =========================== DB okuma yardimcilari =========================== */

    private long count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Long.class, args);
    }

    private String tripStatus(long tripId) {
        return jdbcTemplate.queryForObject("SELECT status FROM trips WHERE id = ?", String.class, tripId);
    }

    private boolean completedAtIsNull(long tripId) {
        return Boolean.TRUE.equals(
                jdbcTemplate.queryForObject("SELECT completed_at IS NULL FROM trips WHERE id = ?", Boolean.class, tripId));
    }

    private String feeStatus(long tripId) {
        return jdbcTemplate.queryForObject("SELECT status FROM trip_fees WHERE trip_id = ?", String.class, tripId);
    }

    private BigDecimal estimatedFeeOf(long tripId) {
        return jdbcTemplate.queryForObject("SELECT estimated_fee FROM trips WHERE id = ?", BigDecimal.class, tripId);
    }

    private BigDecimal balanceOf(long userId) {
        return jdbcTemplate.queryForObject("SELECT balance FROM wallets WHERE user_id = ?", BigDecimal.class, userId);
    }

    private String vehicleStatus(long vehicleId) {
        return jdbcTemplate.queryForObject("SELECT status FROM vehicles WHERE id = ?", String.class, vehicleId);
    }

    private double vehicleLatitude(long vehicleId) {
        return jdbcTemplate.queryForObject("SELECT latitude FROM vehicles WHERE id = ?", Double.class, vehicleId);
    }

    private double vehicleLongitude(long vehicleId) {
        return jdbcTemplate.queryForObject("SELECT longitude FROM vehicles WHERE id = ?", Double.class, vehicleId);
    }

    private BigDecimal vehicleRemaining(long vehicleId) {
        return jdbcTemplate.queryForObject("SELECT remaining_range_km FROM vehicles WHERE id = ?", BigDecimal.class, vehicleId);
    }

    /** Bir yaris görevinin sonucu: ya dönen değer ya da fırlayan hata (timeout dahil). */
    private record Outcome(Object value, Throwable error) {
        boolean succeeded() {
            return error == null;
        }
    }
}

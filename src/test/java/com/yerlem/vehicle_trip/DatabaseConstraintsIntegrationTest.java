package com.yerlem.vehicle_trip;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Adim 14 / Parca 1 — Veritabani degismezleri (gercek PostgreSQL 16 uzerinde).
 *
 * Bu kisitlar servis katmaninin girdisi oldugu icin (servis katmani
 * ExceptionMessages.collect ile kisit ADINA bakarak 409'a esliyor) DB tarafinda
 * dogru isimle ve gercekten ihlal edildikleri kanitlanir. Ham INSERT/UPDATE
 * JdbcTemplate ile yapilir (otocommit; sinif duzeyinde @Transactional yok). Her
 * test kendi benzersiz verisini uretir.
 */
class DatabaseConstraintsIntegrationTest extends AbstractIntegrationTest {

    private static final String TRIP_INSERT =
            "INSERT INTO trips (user_id, vehicle_id, status, city, price_per_km, start_latitude, start_longitude, "
                    + "destination_latitude, destination_longitude, estimated_distance_km, estimated_duration_min, "
                    + "estimated_fee) VALUES (?, ?, ?, 'Istanbul', 15.00, 41.0370, 28.9850, 40.9919, 29.0270, 6.13, 10, 91.95)";

    // a) wallets.balance negatif olamaz (ck_wallets_balance -> DataIntegrityViolationException)
    @Test
    @DisplayName("a) wallets.balance negatif UPDATE -> DataIntegrityViolationException")
    void walletBalanceCannotBeNegative() {
        Long userId = createVerifiedUser(new BigDecimal("100.00"));

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE wallets SET balance = -1 WHERE user_id = ?", userId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // b) ayni seyahat icin ikinci TRIP_CHARGE -> uq_wallet_tx_one_charge_per_trip
    @Test
    @DisplayName("b) ayni trip icin ikinci TRIP_CHARGE -> mesajda uq_wallet_tx_one_charge_per_trip")
    void onlyOneTripChargePerTrip() {
        Long userId = createVerifiedUser(new BigDecimal("1000.00"));
        Long vehicleId = createVehicle();
        Long tripId = insertTrip(userId, vehicleId, "CREATED");
        long walletId = walletIdOf(userId);

        String insertCharge = "INSERT INTO wallet_transactions (wallet_id, trip_id, type, amount, balance_after) "
                + "VALUES (?, ?, 'TRIP_CHARGE', 91.95, 0)";
        jdbcTemplate.update(insertCharge, walletId, tripId);

        String message = expectConstraintViolation(insertCharge, walletId, tripId);
        assertThat(message).contains("uq_wallet_tx_one_charge_per_trip");
    }

    // c) ayni arac icin ikinci ACTIVE trip -> uq_trips_one_active_per_vehicle
    @Test
    @DisplayName("c) ayni arac icin ikinci ACTIVE trip -> mesajda uq_trips_one_active_per_vehicle")
    void onlyOneActiveTripPerVehicle() {
        Long user1 = createVerifiedUser(new BigDecimal("1000.00"));
        Long user2 = createVerifiedUser(new BigDecimal("1000.00"));
        Long vehicleId = createVehicle();
        insertTrip(user1, vehicleId, "ACTIVE");

        String message = expectConstraintViolation(TRIP_INSERT, user2, vehicleId, "ACTIVE");
        assertThat(message).contains("uq_trips_one_active_per_vehicle");
    }

    // d) ayni kullanici icin ikinci ACTIVE trip -> uq_trips_one_active_per_user
    @Test
    @DisplayName("d) ayni kullanici icin ikinci ACTIVE trip -> mesajda uq_trips_one_active_per_user")
    void onlyOneActiveTripPerUser() {
        Long userId = createVerifiedUser(new BigDecimal("1000.00"));
        Long vehicle1 = createVehicle();
        Long vehicle2 = createVehicle();
        insertTrip(userId, vehicle1, "ACTIVE");

        // ikinci ACTIVE trip farkli aracla -> arac indeksi degil, kullanici indeksi ihlal edilir
        String message = expectConstraintViolation(TRIP_INSERT, userId, vehicle2, "ACTIVE");
        assertThat(message).contains("uq_trips_one_active_per_user");
    }

    // e) ayni (trip, recorded_at) iki konum -> uq_trip_locations_trip_time
    @Test
    @DisplayName("e) ayni (trip, recorded_at) iki konum -> mesajda uq_trip_locations_trip_time")
    void noDuplicateLocationTimestamp() {
        Long userId = createVerifiedUser(new BigDecimal("1000.00"));
        Long vehicleId = createVehicle();
        Long tripId = insertTrip(userId, vehicleId, "ACTIVE");

        String insertLocation = "INSERT INTO trip_locations (trip_id, latitude, longitude, recorded_at) "
                + "VALUES (?, 41.0370, 28.9850, TIMESTAMPTZ '2026-01-01T10:00:00Z')";
        jdbcTemplate.update(insertLocation, tripId);

        String message = expectConstraintViolation(insertLocation, tripId);
        assertThat(message).contains("uq_trip_locations_trip_time");
    }

    // f) ayni (trip, fee_type) iki ucret -> uq_trip_fees_trip_type
    @Test
    @DisplayName("f) ayni (trip, fee_type) iki ucret -> mesajda uq_trip_fees_trip_type")
    void noDuplicateFeeTypePerTrip() {
        Long userId = createVerifiedUser(new BigDecimal("1000.00"));
        Long vehicleId = createVehicle();
        Long tripId = insertTrip(userId, vehicleId, "ACTIVE");

        String insertFee = "INSERT INTO trip_fees (trip_id, fee_type, amount) VALUES (?, 'DISTANCE', 91.95)";
        jdbcTemplate.update(insertFee, tripId);

        String message = expectConstraintViolation(insertFee, tripId);
        assertThat(message).contains("uq_trip_fees_trip_type");
    }

    // g) ayni telefonla ikinci kullanici -> uq_users_phone unique ihlali
    @Test
    @DisplayName("g) ayni telefonla ikinci kullanici -> mesajda uq_users_phone")
    void phoneIsUnique() {
        String phone = nextPhone();
        String insertUser = "INSERT INTO users (full_name, phone, national_id, status) "
                + "VALUES ('Test Kisi', ?, '00000000001', 'VERIFIED')";
        jdbcTemplate.update(insertUser, phone);

        String message = expectConstraintViolation(insertUser, phone);
        assertThat(message).contains("uq_users_phone");
    }

    /* ───── yardimcilar ───── */

    /** trips tablosuna satir ekler ve uretilen id'yi doner (PostgreSQL INSERT ... RETURNING). */
    private long insertTrip(long userId, long vehicleId, String status) {
        return jdbcTemplate.queryForObject(TRIP_INSERT + " RETURNING id", Long.class, userId, vehicleId, status);
    }

    private long walletIdOf(long userId) {
        return jdbcTemplate.queryForObject("SELECT id FROM wallets WHERE user_id = ?", Long.class, userId);
    }

    /** Kisit ihlali beklenen INSERT/UPDATE'i calistirir; exception zincirindeki mesajlari (kucuk harf) doner. */
    private String expectConstraintViolation(String sql, Object... args) {
        try {
            jdbcTemplate.update(sql, args);
            throw new AssertionError("DataIntegrityViolationException beklenirken sorgu basarili oldu: " + sql);
        } catch (DataIntegrityViolationException expected) {
            return messagesOf(expected);
        }
    }
}

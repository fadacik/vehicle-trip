package com.yerlem.vehicle_trip;

import com.yerlem.vehicle_trip.dto.CompleteTripRequest;
import com.yerlem.vehicle_trip.dto.PhotoAddRequest;
import com.yerlem.vehicle_trip.dto.TripSummaryResponse;
import com.yerlem.vehicle_trip.exception.ApiException;
import com.yerlem.vehicle_trip.service.PhotoService;
import com.yerlem.vehicle_trip.service.TripCompletionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Adım 17 / B8 — Fotoğraf + hasar entegrasyon testleri (gerçek PostgreSQL).
 *
 * Senaryolar: (1) START/END EXTERIOR çifti hasar üretir → trip_fees'te DISTANCE+DAMAGE
 * iki CHARGED satır, TEK TRIP_CHARGE = tahmini ücret + 500.00, trip_damages 1 satır;
 * (2) bakiye mesafe ücretine yetip hasara yetmezse 402 ve TAM rollback (TRIP_CHARGE,
 * trip_damages, trip_fees satırlarının hiçbiri yok), bakiye yüklenince tamamlanır;
 * (3) aynı (trip, faz, tür) DB değişmezi uq_trip_photos_trip_phase_type ile korunur.
 */
class PhotoDamageIntegrationTest extends AbstractIntegrationTest {

    private static final double KADIKOY_LAT = 40.9919, KADIKOY_LNG = 29.0270;
    private static final BigDecimal DAMAGE_COST = new BigDecimal("500.00");

    @Autowired
    private PhotoService photoService;
    @Autowired
    private TripCompletionService tripCompletionService;

    // 1) HASARLI TAM AKIŞ: tek TRIP_CHARGE = tahmini + 500.00; iki fee satırı CHARGED.
    @Test
    @DisplayName("START+END EXTERIOR hasar çifti -> 2 fee satiri CHARGED; tek TRIP_CHARGE = tahmini+500.00; trip_damages 1; ozet damageFee 500.00")
    void damageFlow_singleCombinedCharge_andTwoChargedFeeRows() {
        Long userId = createVerifiedUser(new BigDecimal("2000.00"));
        Long vehicleId = createVehicle();
        Long tripId = createCreatedTrip(userId, vehicleId, KADIKOY_LAT, KADIKOY_LNG).id();
        tripService.startTrip(tripId);
        BigDecimal distanceFee = estimatedFeeOf(tripId);
        BigDecimal balanceBefore = balanceOf(userId);

        photoService.addPhoto(tripId, new PhotoAddRequest("START", "EXTERIOR", "mock://start-ext.jpg"));
        photoService.addPhoto(tripId, new PhotoAddRequest("END", "EXTERIOR", "mock://end-damage-ext.jpg"));

        TripSummaryResponse summary = tripCompletionService.completeTrip(tripId,
                new CompleteTripRequest(KADIKOY_LAT, KADIKOY_LNG, null, null));

        assertThat(summary.status().name()).isEqualTo("COMPLETED");
        assertThat(summary.damageFee()).isNotNull();
        assertThat(summary.damageFee().amount()).isEqualByComparingTo(DAMAGE_COST);
        assertThat(summary.damageFee().status().name()).isEqualTo("CHARGED");
        assertThat(summary.photos()).hasSize(2);
        assertThat(summary.damages()).hasSize(1);

        assertThat(count("SELECT COUNT(*) FROM trip_fees WHERE trip_id = ? AND status = 'CHARGED'", tripId))
                .as("DISTANCE + DAMAGE iki satir da CHARGED").isEqualTo(2L);
        assertThat(count("SELECT COUNT(*) FROM trip_fees WHERE trip_id = ? AND fee_type = 'DAMAGE' "
                + "AND amount = ?", tripId, DAMAGE_COST))
                .as("DAMAGE satiri 500.00").isEqualTo(1L);
        assertThat(count("SELECT COUNT(*) FROM wallet_transactions WHERE trip_id = ? AND type = 'TRIP_CHARGE'", tripId))
                .as("TEK TRIP_CHARGE").isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("SELECT amount FROM wallet_transactions "
                + "WHERE trip_id = ? AND type = 'TRIP_CHARGE'", BigDecimal.class, tripId))
                .as("tahsilat = mesafe + hasar").isEqualByComparingTo(distanceFee.add(DAMAGE_COST));
        assertThat(balanceOf(userId)).isEqualByComparingTo(balanceBefore.subtract(distanceFee).subtract(DAMAGE_COST));
        assertThat(count("SELECT COUNT(*) FROM trip_damages WHERE trip_id = ? AND photo_type = 'EXTERIOR' "
                + "AND damage_kind = 'DAMAGE'", tripId))
                .as("tek hasar kaydi").isEqualTo(1L);
    }

    // 2) BAKİYE YETERSİZ (mesafeye yetiyor, hasarla yetmiyor) → 402 + TAM rollback → retry.
    @Test
    @DisplayName("bakiye yalnizca mesafe ucretine yeter -> complete 402; DB: trip ACTIVE, TRIP_CHARGE/damages/fees 0 satir; bakiye yuklenince COMPLETED")
    void insufficientBalanceForDamage_fullRollback_thenRetryCompletes() {
        Long userId = createVerifiedUser(new BigDecimal("500.00"));
        Long vehicleId = createVehicle();
        Long tripId = createCreatedTrip(userId, vehicleId, KADIKOY_LAT, KADIKOY_LNG).id();
        tripService.startTrip(tripId);
        BigDecimal distanceFee = estimatedFeeOf(tripId);
        // Bakiyeyi TAM mesafe ücretine çek: start geçti; hasarla birlikte yetersiz kalır.
        jdbcTemplate.update("UPDATE wallets SET balance = ? WHERE user_id = ?", distanceFee, userId);

        photoService.addPhoto(tripId, new PhotoAddRequest("START", "EXTERIOR", "mock://start-ext.jpg"));
        photoService.addPhoto(tripId, new PhotoAddRequest("END", "EXTERIOR", "mock://end-damage-ext.jpg"));

        CompleteTripRequest request = new CompleteTripRequest(KADIKOY_LAT, KADIKOY_LNG, null, null);
        assertThatThrownBy(() -> tripCompletionService.completeTrip(tripId, request))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
                    assertThat(((ApiException) ex).getCode()).isEqualTo("PAYMENT_FAILED");
                });

        assertThat(tripStatus(tripId)).as("trip ACTIVE kalmali").isEqualTo("ACTIVE");
        assertThat(count("SELECT COUNT(*) FROM wallet_transactions WHERE trip_id = ? AND type = 'TRIP_CHARGE'", tripId))
                .as("TRIP_CHARGE yok").isZero();
        assertThat(count("SELECT COUNT(*) FROM trip_damages WHERE trip_id = ?", tripId))
                .as("hasar kaydi rollback edildi").isZero();
        assertThat(count("SELECT COUNT(*) FROM trip_fees WHERE trip_id = ?", tripId))
                .as("DISTANCE ve DAMAGE satirlari rollback edildi").isZero();

        jdbcTemplate.update("UPDATE wallets SET balance = ? WHERE user_id = ?", new BigDecimal("2000.00"), userId);
        TripSummaryResponse retry = tripCompletionService.completeTrip(tripId, request);

        assertThat(retry.status().name()).as("retry COMPLETED").isEqualTo("COMPLETED");
        assertThat(count("SELECT COUNT(*) FROM wallet_transactions WHERE trip_id = ? AND type = 'TRIP_CHARGE'", tripId))
                .as("retry sonrasi tek TRIP_CHARGE").isEqualTo(1L);
        assertThat(count("SELECT COUNT(*) FROM trip_damages WHERE trip_id = ?", tripId))
                .as("hasar kaydi yeniden uretildi").isEqualTo(1L);
    }

    // 3) DB DEĞİŞMEZİ + servis kuralı: aynı (trip, faz, tür) bir daha yüklenemez.
    @Test
    @DisplayName("ayni (trip,faz,tur): servis 409 PHOTO_ALREADY_EXISTS; dogrudan INSERT ise uq_trip_photos_trip_phase_type ile reddedilir")
    void duplicatePhoto_rejectedByService_andByDbConstraint() {
        Long userId = createVerifiedUser(new BigDecimal("1000.00"));
        Long vehicleId = createVehicle();
        Long tripId = createCreatedTrip(userId, vehicleId, KADIKOY_LAT, KADIKOY_LNG).id();
        photoService.addPhoto(tripId, new PhotoAddRequest("START", "EXTERIOR", "mock://start-ext.jpg"));

        assertThatThrownBy(() -> photoService.addPhoto(tripId,
                new PhotoAddRequest("START", "EXTERIOR", "mock://ikinci.jpg")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("PHOTO_ALREADY_EXISTS"));

        Throwable failure = null;
        try {
            jdbcTemplate.update("INSERT INTO trip_photos (trip_id, phase, photo_type, url) "
                    + "VALUES (?, 'START', 'EXTERIOR', 'mock://direct.jpg')", tripId);
        } catch (DataAccessException ex) {
            failure = ex;
        }
        assertThat(failure).as("dogrudan INSERT de reddedilmeli").isNotNull();
        assertThat(messagesOf(failure)).contains("uq_trip_photos_trip_phase_type");
        assertThat(count("SELECT COUNT(*) FROM trip_photos WHERE trip_id = ?", tripId)).isEqualTo(1L);
    }

    /* =============================== yardımcılar =============================== */

    private long count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Long.class, args);
    }

    private String tripStatus(long tripId) {
        return jdbcTemplate.queryForObject("SELECT status FROM trips WHERE id = ?", String.class, tripId);
    }

    private BigDecimal estimatedFeeOf(long tripId) {
        return jdbcTemplate.queryForObject("SELECT estimated_fee FROM trips WHERE id = ?", BigDecimal.class, tripId);
    }

    private BigDecimal balanceOf(long userId) {
        return jdbcTemplate.queryForObject("SELECT balance FROM wallets WHERE user_id = ?", BigDecimal.class, userId);
    }
}

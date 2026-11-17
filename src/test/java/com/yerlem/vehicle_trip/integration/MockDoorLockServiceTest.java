package com.yerlem.vehicle_trip.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Adım 17 / A8 — MockDoorLockService: sayaçlı hata enjeksiyonu (her hata bir sayaç
 * tüketir), idempotency (durum ne olursa olsun ikinci aynı çağrı başarılı), sayaç
 * dışındayken her zaman başarı, reset ve negatif sayaç koruması.
 */
class MockDoorLockServiceTest {

    private MockDoorLockService service;

    @BeforeEach
    void setUp() {
        service = new MockDoorLockService();
    }

    @Test
    @DisplayName("sayaç 2 iken: ilk iki unlock hata, sayaç biter, üçüncü başarılı; sayaçlar sıfıra iner")
    void unlockFailureCounter_decrementsThenSucceeds() {
        service.setFailureCounters(2, 0);

        assertThatThrownBy(() -> service.unlock(1L)).isInstanceOf(DoorServiceException.class);
        assertThat(service.getFailUnlockCount()).isEqualTo(1);
        assertThatThrownBy(() -> service.unlock(1L)).isInstanceOf(DoorServiceException.class);
        assertThat(service.getFailUnlockCount()).isZero();
        assertThatCode(() -> service.unlock(1L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("lock sayaçları unlock sayaçlarından bağımsız çalışır")
    void lockCounterIsIndependent() {
        service.setFailureCounters(0, 1);

        assertThatCode(() -> service.unlock(1L)).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.lock(1L)).isInstanceOf(DoorServiceException.class);
        assertThatCode(() -> service.lock(1L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("idempotency: sayaç yokken tekrar tekrar açmak/kilitlemek başarılıdır (durum ne olursa olsun)")
    void idempotentWithoutCounters() {
        assertThatCode(() -> {
            service.unlock(7L);
            service.unlock(7L);
            service.lock(7L);
            service.lock(7L);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("setFailureCounters(null, x): yalnızca lock sayacını değiştirir, unlock sayacına dokunmaz")
    void setFailureCounters_nullKeeps() {
        service.setFailureCounters(3, 0);
        service.setFailureCounters(null, 2);

        assertThat(service.getFailUnlockCount()).isEqualTo(3);
        assertThat(service.getFailLockCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("reset: sayaçları sıfırlar (testler arası sızıntı olmaz)")
    void resetClearsCounters() {
        service.setFailureCounters(5, 5);
        service.reset();

        assertThat(service.getFailUnlockCount()).isZero();
        assertThat(service.getFailLockCount()).isZero();
        assertThatCode(() -> service.unlock(1L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("negatif sayaç reddedilir (IllegalArgumentException)")
    void negativeCounterRejected() {
        assertThatThrownBy(() -> service.setFailureCounters(-1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

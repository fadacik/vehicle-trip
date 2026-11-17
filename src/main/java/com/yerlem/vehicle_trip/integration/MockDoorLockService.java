package com.yerlem.vehicle_trip.integration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mock kapı kilidi servisi (Adım 17 / A3). Sayaçlı hata enjeksiyonu: sayaç > 0 ise
 * çağrı bir azaltılır ve DoorServiceException fırlatılır; aksi halde başarılıdır.
 * İDEMPOTENT: zaten açık kapıyı açmak / zaten kilitli kapıyı kilitlemek başarılıdır.
 *
 * KISIT (README'de belirtildi): kapı durumu bellekte TEK JVM örneğinde tutulur;
 * çok örnekli (multi-instance) dağıtımda sayaç ve kapı durumu örnekler arası paylaşılıp
 * doğru çalışmaz — bu yalnızca mock/geliştirme davranışıdır.
 */
@Slf4j
@Component
public class MockDoorLockService implements DoorLockService {

    private final AtomicInteger failUnlockCount = new AtomicInteger(0);
    private final AtomicInteger failLockCount = new AtomicInteger(0);

    /** Araç bazında kapı durumu: true = açık, false = kilitli. */
    private final Map<Long, Boolean> doorOpenByVehicle = new ConcurrentHashMap<>();

    @Override
    public void unlock(Long vehicleId) {
        consumeFailure(failUnlockCount, "kapı açılamadı", vehicleId);
        doorOpenByVehicle.put(vehicleId, Boolean.TRUE);
        log.debug("Mock kapı açıldı. vehicleId={}", vehicleId);
    }

    @Override
    public void lock(Long vehicleId) {
        consumeFailure(failLockCount, "kapı kilitlenemedi", vehicleId);
        doorOpenByVehicle.put(vehicleId, Boolean.FALSE);
        log.debug("Mock kapı kilitlendi. vehicleId={}", vehicleId);
    }

    /** Geliştirme kontrolü/entegrasyon testi: kalan hata sayaçlarını ayarlar (null = dokunma). */
    public void setFailureCounters(Integer unlockErrors, Integer lockErrors) {
        if (unlockErrors != null) {
            failUnlockCount.set(requireNonNegative(unlockErrors, "failUnlockCount"));
        }
        if (lockErrors != null) {
            failLockCount.set(requireNonNegative(lockErrors, "failLockCount"));
        }
    }

    public int getFailUnlockCount() {
        return failUnlockCount.get();
    }

    public int getFailLockCount() {
        return failLockCount.get();
    }

    /** Test/entegrasyon temizliği: sayaçları ve bellek içi kapı durumunu sıfırlar. */
    public void reset() {
        failUnlockCount.set(0);
        failLockCount.set(0);
        doorOpenByVehicle.clear();
    }

    private int requireNonNegative(int value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " negatif olamaz.");
        }
        return value;
    }

    private void consumeFailure(AtomicInteger counter, String action, Long vehicleId) {
        int previous = counter.getAndUpdate(current -> current > 0 ? current - 1 : 0);
        if (previous > 0) {
            log.warn("Mock kapı hatası enjekte edildi. vehicleId={}, kalanHata={}", vehicleId, previous - 1);
            throw new DoorServiceException("Kapı servisi yanıt vermedi: " + action + " (mock hata enjeksiyonu).");
        }
    }
}

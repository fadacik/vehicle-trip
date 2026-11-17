package com.yerlem.vehicle_trip.dto;

/** Geliştirme kontrolü: mock kapı servisine enjekte edilecek hata sayaçları (null = dokunma). */
public record MockDoorControlRequest(
        Integer failUnlockCount,
        Integer failLockCount) {
}

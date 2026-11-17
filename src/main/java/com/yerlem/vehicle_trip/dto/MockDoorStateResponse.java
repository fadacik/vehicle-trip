package com.yerlem.vehicle_trip.dto;

/** Geliştirme kontrolü: mock kapı servisinin anlık hata sayaçları. */
public record MockDoorStateResponse(
        int failUnlockCount,
        int failLockCount) {
}

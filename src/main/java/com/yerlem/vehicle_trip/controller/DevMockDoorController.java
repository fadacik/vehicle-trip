package com.yerlem.vehicle_trip.controller;

import com.yerlem.vehicle_trip.dto.MockDoorControlRequest;
import com.yerlem.vehicle_trip.dto.MockDoorStateResponse;
import com.yerlem.vehicle_trip.exception.ApiException;
import com.yerlem.vehicle_trip.integration.MockDoorLockService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * YALNIZCA GELİŞTİRME (Adım 17 / A4): mock kapı servisine hata enjekte etmek için
 * kontrol uç noktaları. app.dev.mock-control-enabled=false yapılırsa bean hiç
 * oluşmaz (üretimde kapalı tutulmalı).
 */
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.dev.mock-control-enabled", havingValue = "true")
public class DevMockDoorController {

    private final MockDoorLockService doorLockService;

    /** Hata sayaçlarını ayarlar: {"failUnlockCount": 1, "failLockCount": 0}. */
    @PostMapping("/api/dev/mock/door")
    public ResponseEntity<MockDoorStateResponse> setFailures(@RequestBody MockDoorControlRequest request) {
        try {
            doorLockService.setFailureCounters(request.failUnlockCount(), request.failLockCount());
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ex.getMessage());
        }
        return ResponseEntity.ok(currentState());
    }

    /** Anlık hata sayaçlarını döner. */
    @GetMapping("/api/dev/mock/door")
    public ResponseEntity<MockDoorStateResponse> getFailures() {
        return ResponseEntity.ok(currentState());
    }

    private MockDoorStateResponse currentState() {
        return new MockDoorStateResponse(doorLockService.getFailUnlockCount(), doorLockService.getFailLockCount());
    }
}

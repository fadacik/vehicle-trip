package com.yerlem.vehicle_trip.controller;

import com.yerlem.vehicle_trip.dto.VehicleDetailResponse;
import com.yerlem.vehicle_trip.dto.VehicleResponse;
import com.yerlem.vehicle_trip.dto.VehicleStatusRequest;
import com.yerlem.vehicle_trip.enums.VehicleStatus;
import com.yerlem.vehicle_trip.service.VehicleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/vehicles")
@RequiredArgsConstructor
public class VehicleController {

    private final VehicleService vehicleService;

    /** Tüm araçlar veya durumuna göre filtreleme. */
    @GetMapping
    public ResponseEntity<List<VehicleResponse>> list(
            @RequestParam(required = false) VehicleStatus status) {
        return ResponseEntity.ok(vehicleService.listVehicles(status));
    }

    /** Tek araç detayı — kullanım alanları dahil. */
    @GetMapping("/{id}")
    public ResponseEntity<VehicleDetailResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(vehicleService.getVehicle(id));
    }

    /** Manuel durum değişikliği (AVAILABLE ↔ MAINTENANCE). */
    @PostMapping("/{id}/status")
    public ResponseEntity<VehicleResponse> changeStatus(
            @PathVariable Long id,
            @Valid @RequestBody VehicleStatusRequest request) {
        return ResponseEntity.ok(vehicleService.changeStatus(id, request.status()));
    }
}


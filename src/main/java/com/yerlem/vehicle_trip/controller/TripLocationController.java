package com.yerlem.vehicle_trip.controller;

import com.yerlem.vehicle_trip.dto.LocationRecordRequest;
import com.yerlem.vehicle_trip.dto.TripLocationResponse;
import com.yerlem.vehicle_trip.dto.TripWarningResponse;
import com.yerlem.vehicle_trip.service.TripLocationService;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class TripLocationController {

    private final TripLocationService tripLocationService;

    /**
     * Konum kaydı (D9/D10). Yeni kayıt 201 + duplicate=false; aynı recordedAt ile gelen tekrar
     * 200 + duplicate=true ile mevcut kaydı döner (yeni satır eklenmez).
     */
    @PostMapping("/api/trips/{id}/locations")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Yeni konum kaydi olusturuldu (duplicate=false)",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = TripLocationResponse.class))),
            @ApiResponse(responseCode = "200", description = "Ayni recordedAt tekrari: yeni satir eklenmedi, mevcut kayit dondu (duplicate=true)",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = TripLocationResponse.class)))
    })
    public ResponseEntity<TripLocationResponse> record(@PathVariable Long id,
                                                       @Valid @RequestBody LocationRecordRequest request) {
        TripLocationResponse response = tripLocationService.recordLocation(id, request);
        if (response.duplicate()) {
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.created(URI.create("/api/trips/" + id + "/locations/" + response.id()))
                .body(response);
    }

    /** Seyahat konumları, zaman sırasıyla (eski → yeni). */
    @GetMapping("/api/trips/{id}/locations")
    public ResponseEntity<List<TripLocationResponse>> getLocations(@PathVariable Long id) {
        return ResponseEntity.ok(tripLocationService.getLocations(id));
    }

    /** Seyahat uyarıları (hız limiti vb.). */
    @GetMapping("/api/trips/{id}/warnings")
    public ResponseEntity<List<TripWarningResponse>> getWarnings(@PathVariable Long id) {
        return ResponseEntity.ok(tripLocationService.getWarnings(id));
    }
}

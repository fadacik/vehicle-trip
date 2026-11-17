package com.yerlem.vehicle_trip.controller;

import com.yerlem.vehicle_trip.dto.TripCreateRequest;
import com.yerlem.vehicle_trip.dto.TripResponse;
import com.yerlem.vehicle_trip.service.TripService;
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
public class TripController {

    private final TripService tripService;

    /** Seyahat oluşturma: tahmini mesafe/süre/ücret hesaplanır (menzil ve bakiye başlatmada kontrol edilir). */
    @PostMapping("/api/trips")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Seyahat olusturuldu (tahminler hesaplandi)",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = TripResponse.class)))
    })
    public ResponseEntity<TripResponse> create(@Valid @RequestBody TripCreateRequest request) {
        TripResponse response = tripService.createTrip(request);
        return ResponseEntity.created(URI.create("/api/trips/" + response.id())).body(response);
    }

    /** Seyahat başlatma: aracı kilitler, menzil/bakiye kontrolü yapar, trip ACTIVE + araç IN_USE olur. */
    @PostMapping("/api/trips/{id}/start")
    public ResponseEntity<TripResponse> start(@PathVariable Long id) {
        return ResponseEntity.ok(tripService.startTrip(id));
    }

    /** Tek seyahat. */
    @GetMapping("/api/trips/{id}")
    public ResponseEntity<TripResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(tripService.getTrip(id));
    }

    /** Kullanıcının seyahatleri. */
    @GetMapping("/api/users/{userId}/trips")
    public ResponseEntity<List<TripResponse>> getUserTrips(@PathVariable Long userId) {
        return ResponseEntity.ok(tripService.getUserTrips(userId));
    }
}

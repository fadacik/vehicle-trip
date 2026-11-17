package com.yerlem.vehicle_trip.controller;

import com.yerlem.vehicle_trip.dto.CompleteTripRequest;
import com.yerlem.vehicle_trip.dto.TripSummaryResponse;
import com.yerlem.vehicle_trip.service.TripCompletionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class TripCompletionController {

    private final TripCompletionService tripCompletionService;

    /**
     * Seyahati tamamlar ve ücreti tahsil eder (D7, D8, D14, D15, D16).
     * 200 döner; trip zaten COMPLETED ise (idempotent) hiçbir yazma yapmadan aynı özeti döner.
     */
    @PostMapping("/api/trips/{id}/complete")
    public ResponseEntity<TripSummaryResponse> complete(@PathVariable Long id,
                                                        @Valid @RequestBody CompleteTripRequest request) {
        return ResponseEntity.ok(tripCompletionService.completeTrip(id, request));
    }
}

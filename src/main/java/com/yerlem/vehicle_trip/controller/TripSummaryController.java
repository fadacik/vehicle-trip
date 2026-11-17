package com.yerlem.vehicle_trip.controller;

import com.yerlem.vehicle_trip.dto.TimelineEventResponse;
import com.yerlem.vehicle_trip.dto.TripSummaryResponse;
import com.yerlem.vehicle_trip.service.TripSummaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class TripSummaryController {

    private final TripSummaryService tripSummaryService;

    /** Seyahat özeti: her trip durumunda döner; ücret, uyarılar, arızalar ve gecikme açıklaması dahil. */
    @GetMapping("/api/trips/{id}/summary")
    public ResponseEntity<TripSummaryResponse> getSummary(@PathVariable Long id) {
        return ResponseEntity.ok(tripSummaryService.getSummary(id));
    }

    /** Zaman çizelgesi: olaylar zaman sırasıyla (eşit zamanda tür sırası belirlidir). */
    @GetMapping("/api/trips/{id}/timeline")
    public ResponseEntity<List<TimelineEventResponse>> getTimeline(@PathVariable Long id) {
        return ResponseEntity.ok(tripSummaryService.getTimeline(id));
    }
}

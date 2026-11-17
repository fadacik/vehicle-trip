package com.yerlem.vehicle_trip.controller;

import com.yerlem.vehicle_trip.dto.FaultReportRequest;
import com.yerlem.vehicle_trip.dto.FaultReportResponse;
import com.yerlem.vehicle_trip.service.FaultService;
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
public class FaultController {

    private final FaultService faultService;

    /** Arıza bildirimi (D11, D12): yalnızca ACTIVE seyahate; araç MAINTENANCE olur, seyahat devam eder. */
    @PostMapping("/api/trips/{id}/faults")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Ariza kaydi olusturuldu; aracin yeni durumu yanitta",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = FaultReportResponse.class)))
    })
    public ResponseEntity<FaultReportResponse> report(@PathVariable Long id,
                                                      @Valid @RequestBody FaultReportRequest request) {
        FaultReportResponse response = faultService.reportFault(id, request);
        return ResponseEntity.created(URI.create("/api/trips/" + id + "/faults/" + response.id()))
                .body(response);
    }

    /** Seyahatin arıza bildirimleri, zaman sırasına (eski → yeni) göre. */
    @GetMapping("/api/trips/{id}/faults")
    public ResponseEntity<List<FaultReportResponse>> getFaults(@PathVariable Long id) {
        return ResponseEntity.ok(faultService.getFaults(id));
    }
}

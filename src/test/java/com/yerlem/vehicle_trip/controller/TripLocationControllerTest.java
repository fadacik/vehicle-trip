package com.yerlem.vehicle_trip.controller;

import com.yerlem.vehicle_trip.dto.TripLocationResponse;
import com.yerlem.vehicle_trip.service.TripLocationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Adim 14 / Parca 3 — TripLocationController WebMvc dilimi.
 * Yeni kayit 201 + duplicate=false; ayni recordedAt tekrar 200 + duplicate=true (controller'in
 * duplicate dallanmasini dogrular). eksik latitude -> 400.
 */
@WebMvcTest(TripLocationController.class)
class TripLocationControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    TripLocationService tripLocationService;

    private TripLocationResponse response(boolean duplicate) {
        return new TripLocationResponse(500L, 41.0, 29.0, Instant.parse("2026-11-16T00:00:00Z"),
                "Istanbul", new BigDecimal("50.00"), duplicate);
    }

    @Test
    void konum_yeni_201_duplicateFalse() throws Exception {
        when(tripLocationService.recordLocation(anyLong(), any())).thenReturn(response(false));
        mockMvc.perform(post("/api/trips/1/locations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":41.0,\"longitude\":29.0,\"speedKmh\":50}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/trips/1/locations/500"))
                .andExpect(jsonPath("$.id").value(500))
                .andExpect(jsonPath("$.duplicate").value(false));
    }

    @Test
    void konum_duplicate_200_duplicateTrue() throws Exception {
        when(tripLocationService.recordLocation(anyLong(), any())).thenReturn(response(true));
        mockMvc.perform(post("/api/trips/1/locations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":41.0,\"longitude\":29.0,\"speedKmh\":50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true));
    }

    @Test
    void konum_latitudeEksik_400() throws Exception {
        mockMvc.perform(post("/api/trips/1/locations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"longitude\":29.0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.latitude").exists());
    }
}

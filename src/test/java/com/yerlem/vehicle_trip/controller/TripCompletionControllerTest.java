package com.yerlem.vehicle_trip.controller;

import com.yerlem.vehicle_trip.dto.TripSummaryResponse;
import com.yerlem.vehicle_trip.enums.TripStatus;
import com.yerlem.vehicle_trip.exception.ApiException;
import com.yerlem.vehicle_trip.service.TripCompletionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Adim 14 / Parca 3 — TripCompletionController WebMvc dilimi.
 * eksik latitude -> 400 VALIDATION_ERROR; NOT_AT_ENDPOINT -> 422 + code; basarili -> 200 + ozet.
 */
@WebMvcTest(TripCompletionController.class)
class TripCompletionControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    TripCompletionService tripCompletionService;

    @Test
    void complete_latitudeEksik_400_VALIDATION_ERROR() throws Exception {
        mockMvc.perform(post("/api/trips/1/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"longitude\":29.0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.latitude").exists());
    }

    @Test
    void complete_varisNoktasiUzak_422_NOT_AT_ENDPOINT() throws Exception {
        when(tripCompletionService.completeTrip(anyLong(), any())).thenThrow(
                new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NOT_AT_ENDPOINT",
                        "Son konum varis noktalarindan birine uzak."));
        mockMvc.perform(post("/api/trips/1/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":45.0,\"longitude\":35.0}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.code").value("NOT_AT_ENDPOINT"));
    }

    @Test
    void complete_basarili_200_ve_ozetAlanlari() throws Exception {
        TripSummaryResponse summary = new TripSummaryResponse(
                1L, TripStatus.COMPLETED, 2L, 3L, "Istanbul", new BigDecimal("15.00"),
                Instant.parse("2026-11-16T00:00:00Z"), Instant.parse("2026-11-16T00:30:00Z"),
                new BigDecimal("10.00"), new BigDecimal("10.50"), 20, 22, new BigDecimal("150.00"),
                null, null, List.of(), List.of(), List.of(), List.of(), List.of(), 5,
                "Seyahat suresi tolerans icinde tamamlandi.");
        when(tripCompletionService.completeTrip(anyLong(), any())).thenReturn(summary);
        mockMvc.perform(post("/api/trips/1/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":40.9919,\"longitude\":29.0270}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tripId").value(1))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }
}

package com.yerlem.vehicle_trip.controller;

import com.yerlem.vehicle_trip.dto.TripResponse;
import com.yerlem.vehicle_trip.enums.TripStatus;
import com.yerlem.vehicle_trip.exception.ApiException;
import com.yerlem.vehicle_trip.service.TripService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Adim 14 / Parca 3 — TripController WebMvc dilimi.
 * start basarili -> 200; INVALID_TRIP_STATE -> 409; olmayan trip GET -> 404 TRIP_NOT_FOUND;
 * ObjectOptimisticLockingFailureException -> 409 CONCURRENT_MODIFICATION;
 * beklenmeyen RuntimeException -> 500 INTERNAL_ERROR (istisna ayrintisi sizmaz).
 */
@WebMvcTest(TripController.class)
class TripControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    TripService tripService;

    private TripResponse activeTrip() {
        return new TripResponse(1L, TripStatus.ACTIVE, 2L, 3L, "Istanbul", new BigDecimal("15.00"),
                41.0370, 28.9850, 40.9919, 29.0270,
                new BigDecimal("6.13"), 10, new BigDecimal("91.95"),
                Instant.parse("2026-11-16T00:00:00Z"), Instant.parse("2026-11-16T00:05:00Z"));
    }

    @Test
    void start_basarili_200() throws Exception {
        when(tripService.startTrip(1L)).thenReturn(activeTrip());
        mockMvc.perform(post("/api/trips/1/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void start_gecersizDurum_409_INVALID_TRIP_STATE() throws Exception {
        when(tripService.startTrip(1L)).thenThrow(
                new ApiException(HttpStatus.CONFLICT, "INVALID_TRIP_STATE", "Seyahat baslatilamaz."));
        mockMvc.perform(post("/api/trips/1/start"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("INVALID_TRIP_STATE"));
    }

    @Test
    void get_bulunamadi_404_TRIP_NOT_FOUND() throws Exception {
        when(tripService.getTrip(404L)).thenThrow(
                new ApiException(HttpStatus.NOT_FOUND, "TRIP_NOT_FOUND", "Seyahat bulunamadi."));
        mockMvc.perform(get("/api/trips/404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
    }

    @Test
    void optimistikKilit_409_CONCURRENT_MODIFICATION() throws Exception {
        when(tripService.startTrip(1L)).thenThrow(new ObjectOptimisticLockingFailureException("Trip", 0));
        mockMvc.perform(post("/api/trips/1/start"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    }

    @Test
    void beklenmeyenHata_500_INTERNAL_ERROR_detaySizmaz() throws Exception {
        when(tripService.startTrip(1L)).thenThrow(new RuntimeException("gizli-ic-detay-12345"));
        mockMvc.perform(post("/api/trips/1/start"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Beklenmeyen bir hata olustu."))
                .andExpect(content().string(not(containsString("gizli-ic-detay-12345"))));
    }
}

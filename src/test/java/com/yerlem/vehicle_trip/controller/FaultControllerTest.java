package com.yerlem.vehicle_trip.controller;

import com.yerlem.vehicle_trip.dto.FaultReportResponse;
import com.yerlem.vehicle_trip.enums.FaultType;
import com.yerlem.vehicle_trip.enums.VehicleStatus;
import com.yerlem.vehicle_trip.service.FaultService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Adim 14 / Parca 3 — FaultController WebMvc dilimi (faultType/description dogrulama + 201 + Location). */
@WebMvcTest(FaultController.class)
class FaultControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    FaultService faultService;

    @Test
    void fault_bosTur_400() throws Exception {
        mockMvc.perform(post("/api/trips/1/faults")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"faultType\":\"\",\"description\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.faultType").exists());
    }

    @Test
    void fault_aciklama501Karakter_400() throws Exception {
        String longDesc = "a".repeat(501);
        mockMvc.perform(post("/api/trips/1/faults")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"faultType\":\"ENGINE\",\"description\":\"" + longDesc + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.description").exists());
    }

    @Test
    void fault_basarili_201_ve_Location() throws Exception {
        when(faultService.reportFault(anyLong(), any())).thenReturn(new FaultReportResponse(
                99L, FaultType.ENGINE, "motor kizdi", Instant.parse("2026-11-16T00:00:00Z"),
                1L, 5L, VehicleStatus.MAINTENANCE));
        mockMvc.perform(post("/api/trips/1/faults")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"faultType\":\"ENGINE\",\"description\":\"motor kizdi\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/trips/1/faults/99"))
                .andExpect(jsonPath("$.id").value(99))
                .andExpect(jsonPath("$.faultType").value("ENGINE"))
                .andExpect(jsonPath("$.vehicleStatus").value("MAINTENANCE"));
    }
}

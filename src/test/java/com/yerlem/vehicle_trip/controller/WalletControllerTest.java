package com.yerlem.vehicle_trip.controller;

import com.yerlem.vehicle_trip.dto.WalletResponse;
import com.yerlem.vehicle_trip.service.WalletService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Adim 14 / Parca 3 — WalletController WebMvc dilimi (top-up dogrulama + basarili). */
@WebMvcTest(WalletController.class)
class WalletControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    WalletService walletService;

    private org.springframework.test.web.servlet.RequestBuilder topUp(String json) {
        return post("/api/users/1/wallet/top-up").contentType(MediaType.APPLICATION_JSON).content(json);
    }

    @Test
    void topUp_sifir_400() throws Exception {
        mockMvc.perform(topUp("{\"amount\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.amount").exists());
    }

    @Test
    void topUp_negatif_400() throws Exception {
        mockMvc.perform(topUp("{\"amount\":-10}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.amount").exists());
    }

    @Test
    void topUp_tutarEksik_400() throws Exception {
        mockMvc.perform(topUp("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.amount").exists());
    }

    @Test
    void topUp_basarili_200() throws Exception {
        when(walletService.topUp(anyLong(), any())).thenReturn(new WalletResponse(10L, 1L, new BigDecimal("150.00")));
        mockMvc.perform(topUp("{\"amount\":50.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(1))
                .andExpect(jsonPath("$.balance").exists());
    }
}

package com.yerlem.vehicle_trip.controller;

import com.yerlem.vehicle_trip.dto.RegisterResponse;
import com.yerlem.vehicle_trip.enums.UserStatus;
import com.yerlem.vehicle_trip.service.RegistrationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Adim 14 / Parca 3 — AuthController WebMvc dilimi.
 * Yalnizca controller + GlobalExceptionHandler + dogrulama + Jackson yuklenir; servis @MockitoBean.
 */
@WebMvcTest(AuthController.class)
class AuthControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    RegistrationService registrationService;

    @Test
    void register_gecersizTelefon_400_VALIDATION_ERROR_detailsPhone() throws Exception {
        String body = """
                {"fullName":"Ali Veli","phone":"abc","nationalId":"12345678901"}
                """;
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Istek alanlari gecersiz."))
                .andExpect(jsonPath("$.details.phone").exists());
    }

    @Test
    void register_bozukJson_400_MALFORMED_REQUEST() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ bu gecerli json degil "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void register_basarili_201_ve_alanlar() throws Exception {
        when(registrationService.register(any())).thenReturn(new RegisterResponse(
                7L, "+905551112233", UserStatus.PENDING,
                Instant.parse("2026-11-16T00:00:00Z"), "123456", "Dogrulama kodu gonderildi."));
        String body = """
                {"fullName":"Ali Veli","phone":"+905551112233","nationalId":"12345678901"}
                """;
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(7))
                .andExpect(jsonPath("$.phone").value("+905551112233"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.otp").value("123456"));
    }
}

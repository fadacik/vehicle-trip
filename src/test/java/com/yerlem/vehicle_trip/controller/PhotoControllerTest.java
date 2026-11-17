package com.yerlem.vehicle_trip.controller;

import com.yerlem.vehicle_trip.dto.PhotoResponse;
import com.yerlem.vehicle_trip.enums.PhotoPhase;
import com.yerlem.vehicle_trip.enums.PhotoType;
import com.yerlem.vehicle_trip.exception.ApiException;
import com.yerlem.vehicle_trip.service.PhotoService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Adım 17 / B8 — PhotoController WebMvc dilimi: 201+Location, 400 INVALID_PHOTO, 409, GET 200. */
@WebMvcTest(PhotoController.class)
class PhotoControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    PhotoService photoService;

    private PhotoResponse sample() {
        return new PhotoResponse(1L, PhotoPhase.START, PhotoType.EXTERIOR, "mock://start-ext.jpg",
                Instant.parse("2026-01-01T10:00:00Z"));
    }

    @Test
    void photos_post201_locationHeader() throws Exception {
        when(photoService.addPhoto(anyLong(), any())).thenReturn(sample());
        mockMvc.perform(post("/api/trips/1/photos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phase\":\"START\",\"photoType\":\"EXTERIOR\",\"url\":\"mock://start-ext.jpg\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/trips/1/photos/1"))
                .andExpect(jsonPath("$.phase").value("START"))
                .andExpect(jsonPath("$.photoType").value("EXTERIOR"));
    }

    @Test
    void photos_gecersizFaz_400_INVALID_PHOTO() throws Exception {
        when(photoService.addPhoto(anyLong(), any())).thenThrow(
                new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PHOTO",
                        "Faz geçersiz: yalnızca START veya END olabilir."));
        mockMvc.perform(post("/api/trips/1/photos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phase\":\"ORTA\",\"photoType\":\"EXTERIOR\",\"url\":\"mock://x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PHOTO"));
    }

    @Test
    void photos_mukerrer_409_PHOTO_ALREADY_EXISTS() throws Exception {
        when(photoService.addPhoto(anyLong(), any())).thenThrow(
                new ApiException(HttpStatus.CONFLICT, "PHOTO_ALREADY_EXISTS", "bu fotoğraf zaten yüklü"));
        mockMvc.perform(post("/api/trips/1/photos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phase\":\"START\",\"photoType\":\"EXTERIOR\",\"url\":\"mock://x\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PHOTO_ALREADY_EXISTS"));
    }

    @Test
    void photos_get200_liste() throws Exception {
        when(photoService.getPhotos(anyLong())).thenReturn(List.of(sample()));
        mockMvc.perform(get("/api/trips/1/photos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].phase").value("START"))
                .andExpect(jsonPath("$[0].url").value("mock://start-ext.jpg"));
    }
}

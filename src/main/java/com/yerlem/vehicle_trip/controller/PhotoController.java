package com.yerlem.vehicle_trip.controller;

import com.yerlem.vehicle_trip.dto.PhotoAddRequest;
import com.yerlem.vehicle_trip.dto.PhotoResponse;
import com.yerlem.vehicle_trip.service.PhotoService;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/** Seyahat fotoğrafları (Adım 17 / B3): yükleme ve sıralı liste. */
@RestController
@RequiredArgsConstructor
public class PhotoController {

    private final PhotoService photoService;

    @PostMapping("/api/trips/{id}/photos")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Fotoğraf yüklendi",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = PhotoResponse.class)))
    })
    public ResponseEntity<PhotoResponse> addPhoto(@PathVariable Long id, @RequestBody PhotoAddRequest request) {
        PhotoResponse response = photoService.addPhoto(id, request);
        return ResponseEntity.created(URI.create("/api/trips/" + id + "/photos/" + response.id()))
                .body(response);
    }

    @GetMapping("/api/trips/{id}/photos")
    public ResponseEntity<List<PhotoResponse>> getPhotos(@PathVariable Long id) {
        return ResponseEntity.ok(photoService.getPhotos(id));
    }
}

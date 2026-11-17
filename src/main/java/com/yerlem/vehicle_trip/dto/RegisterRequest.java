package com.yerlem.vehicle_trip.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "Ad soyad zorunludur")
        @Size(max = 150, message = "Ad soyad en fazla 150 karakter olabilir")
        String fullName,

        @NotBlank(message = "Telefon zorunludur")
        @Pattern(regexp = "[+]?[0-9]{10,15}", message = "Telefon 10-15 haneli olmali (basinda + olabilir)")
        String phone,

        @NotBlank(message = "Kimlik numarasi zorunludur")
        @Pattern(regexp = "[0-9]{11}", message = "Kimlik numarasi 11 haneli olmali")
        String nationalId,

        @Size(max = 500, message = "Profil fotografi URL'si en fazla 500 karakter olabilir")
        String profilePhotoUrl
) {
}

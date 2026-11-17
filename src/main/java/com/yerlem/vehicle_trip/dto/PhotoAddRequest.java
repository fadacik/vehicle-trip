package com.yerlem.vehicle_trip.dto;

/**
 * Fotoğraf ekleme isteği (Adım 17 / B3). Düz String taşır: geçersiz faz/tür/url
 * bean validation yerine servis katmanında 400 INVALID_PHOTO ile karşılanır
 * (ödevin istediği hata kodu dönmeli).
 */
public record PhotoAddRequest(
        String phase,
        String photoType,
        String url) {
}

package com.yerlem.vehicle_trip.integration;

import com.yerlem.vehicle_trip.enums.DamageKind;
import com.yerlem.vehicle_trip.enums.PhotoType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Adım 17 / B8 — MockDamageDetectionService'in belirlenimci kuralları. */
class MockDamageDetectionServiceTest {

    private final MockDamageDetectionService service = new MockDamageDetectionService();

    @Test
    @DisplayName("END 'damage' içerir, START içermez → DAMAGE bulgusu (Türkçe açıklamalı)")
    void endDamageDetected() {
        Optional<DamageDetectionService.Finding> finding =
                service.compare(PhotoType.EXTERIOR, "mock://start-ext.jpg", "mock://end-damage-ext.jpg");

        assertThat(finding).isPresent();
        assertThat(finding.get().kind()).isEqualTo(DamageKind.DAMAGE);
        assertThat(finding.get().description()).isNotBlank();
    }

    @Test
    @DisplayName("END 'missing' içerir → MISSING_ITEM bulgusu")
    void endMissingItemDetected() {
        Optional<DamageDetectionService.Finding> finding =
                service.compare(PhotoType.FRONT_SEAT, "mock://start.jpg", "mock://missing-seat-cover.jpg");

        assertThat(finding).isPresent();
        assertThat(finding.get().kind()).isEqualTo(DamageKind.MISSING_ITEM);
    }

    @Test
    @DisplayName("END iki anahtar kelimeyi de içerirse DAMAGE önceliklidir")
    void damageTakesPrecedenceOverMissing() {
        Optional<DamageDetectionService.Finding> finding = service.compare(PhotoType.BACK_SEAT,
                "mock://start.jpg", "mock://damage-and-missing.jpg");

        assertThat(finding).isPresent();
        assertThat(finding.get().kind()).isEqualTo(DamageKind.DAMAGE);
    }

    @Test
    @DisplayName("baştan 'damage' içeren START cezalandırılmaz → bulgu YOK")
    void startAlreadyDamaged_notPunished() {
        Optional<DamageDetectionService.Finding> finding =
                service.compare(PhotoType.EXTERIOR, "mock://start-damage.jpg", "mock://end-damage.jpg");

        assertThat(finding).isEmpty();
    }

    @Test
    @DisplayName("anahtar kelime yoksa ve büyük/küçük harf fark etmez → END 'DAMAGE' büyük harfle de bulunur")
    void caseInsensitive() {
        assertThat(service.compare(PhotoType.EXTERIOR, "mock://start.jpg", "mock://DAMAGE-end.jpg")).isPresent();
        assertThat(service.compare(PhotoType.EXTERIOR, "mock://start.jpg", "mock://clean-end.jpg")).isEmpty();
    }

    @Test
    @DisplayName("null referanslar NPE değil, bulgusuz sonuç üretir")
    void nullReferencesAreSafe() {
        assertThat(service.compare(PhotoType.EXTERIOR, null, null)).isEmpty();
        assertThat(service.compare(PhotoType.EXTERIOR, "mock://s.jpg", null)).isEmpty();
    }
}

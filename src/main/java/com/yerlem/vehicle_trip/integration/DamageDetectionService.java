package com.yerlem.vehicle_trip.integration;

import com.yerlem.vehicle_trip.enums.DamageKind;
import com.yerlem.vehicle_trip.enums.PhotoType;

import java.util.Optional;

/**
 * Başlangıç/bitiş fotoğrafı karşılaştırması soyutlaması (Adım 17 / B4). Gerçek görüntü
 * işleme yok; MockDamageDetectionService belirlenimci kural uygular.
 */
public interface DamageDetectionService {

    /** Aynı türün START ve END referansını karşılaştırır; bulgu yoksa Optional.empty(). */
    Optional<Finding> compare(PhotoType photoType, String startRef, String endRef);

    /** Tespit edilen bulgu: türü ve Türkçe açıklaması. */
    record Finding(DamageKind kind, String description) {
    }
}

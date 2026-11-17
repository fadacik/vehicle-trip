package com.yerlem.vehicle_trip.config;

import com.yerlem.vehicle_trip.enums.PhotoType;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.Map;

/**
 * application.yml -> app.damage.* (Adım 17 / B4). Hasar ve eksik parça birim
 * maliyetleri; sihirli sayı kodda yok, deterministik testler bu değerleri kullanır.
 */
@ConfigurationProperties(prefix = "app.damage")
public record DamageProperties(
        Map<PhotoType, BigDecimal> damageCost,
        BigDecimal missingItemCost) {
}

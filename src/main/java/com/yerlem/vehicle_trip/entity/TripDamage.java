package com.yerlem.vehicle_trip.entity;

import com.yerlem.vehicle_trip.enums.DamageKind;
import com.yerlem.vehicle_trip.enums.PhotoType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Tamamlamada üretilen hasar kaydı (Adım 17 / B1). Trip başına tür başına tek kayıt:
 * uq_trip_damages_trip_photo_type; cost >= 0 CHECK'iyle korunur.
 */
@Entity
@Table(name = "trip_damages")
@Getter
@Setter
@NoArgsConstructor
public class TripDamage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @Enumerated(EnumType.STRING)
    @Column(name = "photo_type", nullable = false)
    private PhotoType photoType;

    @Enumerated(EnumType.STRING)
    @Column(name = "damage_kind", nullable = false)
    private DamageKind damageKind;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "cost", nullable = false, precision = 12, scale = 2)
    private BigDecimal cost;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt = Instant.now();
}

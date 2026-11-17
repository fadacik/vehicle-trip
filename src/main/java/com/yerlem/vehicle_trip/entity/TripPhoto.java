package com.yerlem.vehicle_trip.entity;

import com.yerlem.vehicle_trip.enums.PhotoPhase;
import com.yerlem.vehicle_trip.enums.PhotoType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Seyahat fotoğrafı (Adım 17 / B1). Gerçek dosya saklanmaz; url/meta bir referanstır
 * (mock). (trip, phase, photo_type) benzersizdir: uq_trip_photos_trip_phase_type.
 */
@Entity
@Table(name = "trip_photos")
@Getter
@Setter
@NoArgsConstructor
public class TripPhoto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @Enumerated(EnumType.STRING)
    @Column(name = "phase", nullable = false)
    private PhotoPhase phase;

    @Enumerated(EnumType.STRING)
    @Column(name = "photo_type", nullable = false)
    private PhotoType photoType;

    @Column(name = "url", nullable = false, length = 500)
    private String url;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt = Instant.now();
}

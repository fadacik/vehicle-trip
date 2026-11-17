package com.yerlem.vehicle_trip.entity;

import com.yerlem.vehicle_trip.enums.FaultType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "fault_reports")
@Getter
@Setter
@NoArgsConstructor
public class FaultReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vehicle_id", nullable = false)
    private Vehicle vehicle;

    @Enumerated(EnumType.STRING)
    @Column(name = "fault_type", nullable = false)
    private FaultType faultType;

    private String description;

    @Column(name = "reported_at", nullable = false)
    private Instant reportedAt = Instant.now();
}

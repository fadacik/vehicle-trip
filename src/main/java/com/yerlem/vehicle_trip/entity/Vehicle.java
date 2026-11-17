package com.yerlem.vehicle_trip.entity;

import com.yerlem.vehicle_trip.enums.VehicleStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "vehicles")
@Getter
@Setter
@NoArgsConstructor
public class Vehicle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String plate;

    @Column(nullable = false)
    private String brand;

    @Column(nullable = false)
    private String model;

    @Column(nullable = false)
    private String city;

    @Column(nullable = false)
    private Double latitude;

    @Column(nullable = false)
    private Double longitude;

    @Column(name = "total_range_km", nullable = false)
    private BigDecimal totalRangeKm;

    @Column(name = "remaining_range_km", nullable = false)
    private BigDecimal remainingRangeKm;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VehicleStatus status = VehicleStatus.AVAILABLE;

    /** Optimistic locking: es zamanli guncellemelerde ikinci yazma OptimisticLockException alir. */
    @Version
    private Long version;
}

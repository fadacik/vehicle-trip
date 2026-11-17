package com.yerlem.vehicle_trip.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "city_tariffs")
@Getter
@Setter
@NoArgsConstructor
public class CityTariff {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String city;

    @Column(name = "price_per_km", nullable = false)
    private BigDecimal pricePerKm;

    @Column(nullable = false, length = 3)
    private String currency = "TRY";
}

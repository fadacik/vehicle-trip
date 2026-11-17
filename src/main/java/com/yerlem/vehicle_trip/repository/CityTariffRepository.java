package com.yerlem.vehicle_trip.repository;

import com.yerlem.vehicle_trip.entity.CityTariff;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CityTariffRepository extends JpaRepository<CityTariff, Long> {
    Optional<CityTariff> findByCityIgnoreCase(String city);
}

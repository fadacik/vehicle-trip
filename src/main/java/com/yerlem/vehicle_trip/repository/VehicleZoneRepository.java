package com.yerlem.vehicle_trip.repository;

import com.yerlem.vehicle_trip.entity.VehicleZone;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VehicleZoneRepository extends JpaRepository<VehicleZone, Long> {
    List<VehicleZone> findByVehicleId(Long vehicleId);
}

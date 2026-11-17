package com.yerlem.vehicle_trip.repository;

import com.yerlem.vehicle_trip.entity.TripDamage;
import com.yerlem.vehicle_trip.enums.PhotoType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TripDamageRepository extends JpaRepository<TripDamage, Long> {

    List<TripDamage> findByTripIdOrderByPhotoTypeAsc(Long tripId);

    boolean existsByTripIdAndPhotoType(Long tripId, PhotoType photoType);
}

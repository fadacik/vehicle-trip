package com.yerlem.vehicle_trip.repository;

import com.yerlem.vehicle_trip.entity.TripWarning;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TripWarningRepository extends JpaRepository<TripWarning, Long> {
    List<TripWarning> findByTripIdOrderByCreatedAtAsc(Long tripId);
}

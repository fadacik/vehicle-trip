package com.yerlem.vehicle_trip.repository;

import com.yerlem.vehicle_trip.entity.TripLocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TripLocationRepository extends JpaRepository<TripLocation, Long> {
    List<TripLocation> findByTripIdOrderByRecordedAtAsc(Long tripId);
    Optional<TripLocation> findFirstByTripIdOrderByRecordedAtDesc(Long tripId);
    Optional<TripLocation> findByTripIdAndRecordedAt(Long tripId, Instant recordedAt);
    long countByTripId(Long tripId);
}

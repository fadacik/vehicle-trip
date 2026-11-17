package com.yerlem.vehicle_trip.repository;

import com.yerlem.vehicle_trip.entity.TripPhoto;
import com.yerlem.vehicle_trip.enums.PhotoPhase;
import com.yerlem.vehicle_trip.enums.PhotoType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TripPhotoRepository extends JpaRepository<TripPhoto, Long> {

    /** Faz, sonra tür sıralı (deterministik liste). */
    List<TripPhoto> findByTripIdOrderByPhaseAscPhotoTypeAsc(Long tripId);

    Optional<TripPhoto> findByTripIdAndPhaseAndPhotoType(Long tripId, PhotoPhase phase, PhotoType photoType);

    boolean existsByTripIdAndPhaseAndPhotoType(Long tripId, PhotoPhase phase, PhotoType photoType);
}

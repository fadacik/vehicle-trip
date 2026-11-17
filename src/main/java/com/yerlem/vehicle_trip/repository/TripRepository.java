package com.yerlem.vehicle_trip.repository;

import com.yerlem.vehicle_trip.entity.Trip;
import com.yerlem.vehicle_trip.enums.TripStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TripRepository extends JpaRepository<Trip, Long> {

    List<Trip> findByUserIdOrderByCreatedAtDesc(Long userId);

    boolean existsByVehicleIdAndStatus(Long vehicleId, TripStatus status);

    boolean existsByUserIdAndStatus(Long userId, TripStatus status);

    /** Tamamlama sirasinda ayni seyahati kilitler: es zamanli iki tamamlama sirayla calisir. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Trip t where t.id = :id")
    Optional<Trip> findByIdForUpdate(@Param("id") Long id);
}

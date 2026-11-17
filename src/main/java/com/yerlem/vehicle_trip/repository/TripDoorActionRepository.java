package com.yerlem.vehicle_trip.repository;

import com.yerlem.vehicle_trip.entity.TripDoorAction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TripDoorActionRepository extends JpaRepository<TripDoorAction, Long> {

    /** Bir seyahatin kapı işlemleri, oluşturulma zamanına göre artan sırada. */
    List<TripDoorAction> findByTripIdOrderByCreatedAtAsc(Long tripId);
}

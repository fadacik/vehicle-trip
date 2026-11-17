package com.yerlem.vehicle_trip.repository;

import com.yerlem.vehicle_trip.entity.TripFee;
import com.yerlem.vehicle_trip.enums.FeeType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TripFeeRepository extends JpaRepository<TripFee, Long> {
    Optional<TripFee> findByTripIdAndFeeType(Long tripId, FeeType feeType);
    List<TripFee> findByTripId(Long tripId);
}

package com.yerlem.vehicle_trip.repository;

import com.yerlem.vehicle_trip.entity.FaultReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FaultReportRepository extends JpaRepository<FaultReport, Long> {
    List<FaultReport> findByTripIdOrderByReportedAtAsc(Long tripId);

    /** Varış kuralı istisnası (D14): trip için arıza kaydı var mı? */
    boolean existsByTripId(Long tripId);
}

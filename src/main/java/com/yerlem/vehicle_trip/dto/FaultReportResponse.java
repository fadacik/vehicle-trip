package com.yerlem.vehicle_trip.dto;

import com.yerlem.vehicle_trip.entity.FaultReport;
import com.yerlem.vehicle_trip.entity.Vehicle;
import com.yerlem.vehicle_trip.enums.FaultType;
import com.yerlem.vehicle_trip.enums.VehicleStatus;

import java.time.Instant;

/**
 * Arıza bildirimi yanıtı (D12): kayıt + aracın yeni durumu.
 */
public record FaultReportResponse(
        Long id,
        FaultType faultType,
        String description,
        Instant reportedAt,
        Long tripId,
        Long vehicleId,
        VehicleStatus vehicleStatus) {

    /** POST yanıtı: araç durumu arıza sonrası KİLİTLİ araç nesnesinden okunur. */
    public static FaultReportResponse from(FaultReport report, Vehicle vehicle) {
        return new FaultReportResponse(
                report.getId(),
                report.getFaultType(),
                report.getDescription(),
                report.getReportedAt(),
                report.getTrip().getId(),
                report.getVehicle().getId(),
                vehicle.getStatus());
    }

    /** GET yanıtı: araç durumu raporun araç ilişkisinden okunur (transaction içinde). */
    public static FaultReportResponse from(FaultReport report) {
        return from(report, report.getVehicle());
    }
}

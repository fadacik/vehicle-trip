package com.yerlem.vehicle_trip.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Arıza bildirimi isteği (D12).
 * faultType servis katmanında doğrulanır (trim + Locale.ROOT ile normalizasyon);
 * geçersiz değer 400 INVALID_FAULT_TYPE döner. Bu yüzden DTO'da String olarak gelir.
 */
public record FaultReportRequest(

        @NotBlank(message = "Arıza türü (faultType) zorunludur.")
        String faultType,

        @Size(max = 500, message = "Açıklama en fazla 500 karakter olabilir.")
        String description) {
}

package com.yerlem.vehicle_trip.dto;

import com.yerlem.vehicle_trip.entity.FaultReport;
import com.yerlem.vehicle_trip.entity.Trip;
import com.yerlem.vehicle_trip.entity.TripDamage;
import com.yerlem.vehicle_trip.entity.TripDoorAction;
import com.yerlem.vehicle_trip.entity.TripFee;
import com.yerlem.vehicle_trip.entity.TripPhoto;
import com.yerlem.vehicle_trip.entity.TripWarning;
import com.yerlem.vehicle_trip.enums.DamageKind;
import com.yerlem.vehicle_trip.enums.DoorAction;
import com.yerlem.vehicle_trip.enums.DoorActionReason;
import com.yerlem.vehicle_trip.enums.DoorActionStatus;
import com.yerlem.vehicle_trip.enums.FeeStatus;
import com.yerlem.vehicle_trip.enums.FaultType;
import com.yerlem.vehicle_trip.enums.PhotoPhase;
import com.yerlem.vehicle_trip.enums.PhotoType;
import com.yerlem.vehicle_trip.enums.TripStatus;
import com.yerlem.vehicle_trip.enums.WarningType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Seyahat özeti (Adım 13 / D13). Her trip durumunda dönebilir; tamamlanmamış
 * seyahatte actual* alanları null olabilir, ücret kaydı yoksa fee null olur,
 * delayExplanation yalnızca COMPLETED seyahatte üretilir.
 */
public record TripSummaryResponse(
        Long tripId,
        TripStatus status,
        Long userId,
        Long vehicleId,
        String city,
        BigDecimal pricePerKm,
        Instant startedAt,
        Instant completedAt,
        BigDecimal estimatedDistanceKm,
        BigDecimal actualDistanceKm,
        Integer estimatedDurationMin,
        Integer actualDurationMin,
        BigDecimal estimatedFee,
        FeeInfo fee,
        FeeInfo damageFee,
        List<WarningInfo> warnings,
        List<FaultInfo> faults,
        List<DoorActionInfo> doorActions,
        List<PhotoInfo> photos,
        List<DamageInfo> damages,
        int locationCount,
        String delayExplanation) {

    /**
     * Tek özet üreticinin kullandığı fabrika: GET summary ve completeTrip yanıtı
     * aynı şekilde oluşur (kod kopyalama yok). Lazy user/vehicle alanlarına
     * eriştiği için aktif transaction içinde çağrılmalıdır.
     */
    public static TripSummaryResponse from(Trip trip, TripFee distanceFee, TripFee damageFee,
                                           List<TripWarning> warnings,
                                           List<FaultReport> faults, List<TripDoorAction> doorActions,
                                           List<TripPhoto> photos, List<TripDamage> damages,
                                           int locationCount, String delayExplanation) {
        return new TripSummaryResponse(
                trip.getId(),
                trip.getStatus(),
                trip.getUser().getId(),
                trip.getVehicle().getId(),
                trip.getCity(),
                trip.getPricePerKm(),
                trip.getStartedAt(),
                trip.getCompletedAt(),
                trip.getEstimatedDistanceKm(),
                trip.getActualDistanceKm(),
                trip.getEstimatedDurationMin(),
                trip.getActualDurationMin(),
                trip.getEstimatedFee(),
                distanceFee == null ? null : FeeInfo.from(distanceFee),
                damageFee == null ? null : FeeInfo.from(damageFee),
                warnings.stream().map(WarningInfo::from).toList(),
                faults.stream().map(FaultInfo::from).toList(),
                doorActions.stream().map(DoorActionInfo::from).toList(),
                photos.stream().map(PhotoInfo::from).toList(),
                damages.stream().map(DamageInfo::from).toList(),
                locationCount,
                delayExplanation);
    }

    /** DISTANCE ücretinin tahsilat durumu; ücret kaydı yoksa özette fee = null. */
    public record FeeInfo(
            BigDecimal amount,
            FeeStatus status,
            Instant chargedAt) {

        public static FeeInfo from(TripFee fee) {
            return new FeeInfo(fee.getAmount(), fee.getStatus(), fee.getChargedAt());
        }
    }

    public record WarningInfo(
            Long id,
            WarningType warningType,
            String message,
            Instant createdAt) {

        public static WarningInfo from(TripWarning warning) {
            return new WarningInfo(warning.getId(), warning.getWarningType(), warning.getMessage(),
                    warning.getCreatedAt());
        }
    }

    public record FaultInfo(
            Long id,
            FaultType faultType,
            String description,
            Instant reportedAt) {

        public static FaultInfo from(FaultReport report) {
            return new FaultInfo(report.getId(), report.getFaultType(), report.getDescription(),
                    report.getReportedAt());
        }
    }

    /** Kapı denetim kaydı (Adım 17 / A7); zaman sıralı liste özette doorActions alanındadır. */
    public record DoorActionInfo(
            Long id,
            DoorAction action,
            DoorActionStatus status,
            DoorActionReason reason,
            String message,
            Instant createdAt) {

        public static DoorActionInfo from(TripDoorAction action) {
            return new DoorActionInfo(action.getId(), action.getAction(), action.getStatus(), action.getReason(),
                    action.getMessage(), action.getCreatedAt());
        }
    }

    /** Yüklenen fotoğraf (Adım 17 / B7). */
    public record PhotoInfo(
            Long id,
            PhotoPhase phase,
            PhotoType photoType,
            String url,
            Instant uploadedAt) {

        public static PhotoInfo from(TripPhoto photo) {
            return new PhotoInfo(photo.getId(), photo.getPhase(), photo.getPhotoType(), photo.getUrl(),
                    photo.getUploadedAt());
        }
    }

    /** Tespit edilen hasar kaydı (Adım 17 / B7); maliyet özetteki damageFee kalemiyle beslenir. */
    public record DamageInfo(
            Long id,
            PhotoType photoType,
            DamageKind damageKind,
            String description,
            BigDecimal cost,
            Instant detectedAt) {

        public static DamageInfo from(TripDamage damage) {
            return new DamageInfo(damage.getId(), damage.getPhotoType(), damage.getDamageKind(),
                    damage.getDescription(), damage.getCost(), damage.getDetectedAt());
        }
    }
}

package com.yerlem.vehicle_trip.service;

import com.yerlem.vehicle_trip.config.DamageProperties;
import com.yerlem.vehicle_trip.entity.Trip;
import com.yerlem.vehicle_trip.entity.TripDamage;
import com.yerlem.vehicle_trip.entity.TripPhoto;
import com.yerlem.vehicle_trip.enums.DamageKind;
import com.yerlem.vehicle_trip.enums.PhotoPhase;
import com.yerlem.vehicle_trip.enums.PhotoType;
import com.yerlem.vehicle_trip.integration.DamageDetectionService;
import com.yerlem.vehicle_trip.repository.TripDamageRepository;
import com.yerlem.vehicle_trip.repository.TripPhotoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Hasar değerlendirmesi (Adım 17 / B4, D22): her fotoğraf türü için YALNIZCA hem START
 * hem END fotoğrafı varsa karşılaştırma yapılır (biri eksikse tür atlanır). Bulgu
 * TripDamage olarak yazılır; toplam tutar (BigDecimal, scale 2 HALF_UP) döner; bulgu
 * yoksa 0.00. Trip başına tür başına tek kayıt garanti edilir (existsBy + DB unique).
 *
 * Çağıranın (TripCompletionService) transaction'ına katılır: tahsilat başarısız olursa
 * hasar kayıtları da geri alınır — tekrar denemede yeniden üretilir.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DamageAssessmentService {

    private final TripPhotoRepository tripPhotoRepository;
    private final TripDamageRepository tripDamageRepository;
    private final DamageDetectionService damageDetectionService;
    private final DamageProperties damageProperties;
    private final Clock clock;

    /** Trip'in START/END fotoğraf çiftlerinden hasar toplamını üretir (bulgu yoksa 0.00). */
    @Transactional
    public BigDecimal assess(Trip trip) {
        Long tripId = trip.getId();
        BigDecimal total = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        for (PhotoType photoType : PhotoType.values()) {
            Optional<TripPhoto> start =
                    tripPhotoRepository.findByTripIdAndPhaseAndPhotoType(tripId, PhotoPhase.START, photoType);
            Optional<TripPhoto> end =
                    tripPhotoRepository.findByTripIdAndPhaseAndPhotoType(tripId, PhotoPhase.END, photoType);
            if (start.isEmpty() || end.isEmpty()) {
                continue; // çift eksik: karşılaştırma yapılmaz (yanıltıcı tek taraf ceza üretmez)
            }
            if (tripDamageRepository.existsByTripIdAndPhotoType(tripId, photoType)) {
                continue; // mükerrer değerlendirme koruması (idempotency + DB unique ikinci savunma)
            }
            Optional<DamageDetectionService.Finding> finding =
                    damageDetectionService.compare(photoType, start.get().getUrl(), end.get().getUrl());
            if (finding.isEmpty()) {
                continue;
            }
            BigDecimal cost = costOf(finding.get().kind(), photoType);
            TripDamage damage = new TripDamage();
            damage.setTrip(trip);
            damage.setPhotoType(photoType);
            damage.setDamageKind(finding.get().kind());
            damage.setDescription(finding.get().description());
            damage.setCost(cost);
            damage.setDetectedAt(Instant.now(clock));
            tripDamageRepository.save(damage);
            total = total.add(cost);
            log.info("Hasar tespit edildi. tripId={}, photoType={}, kind={}, cost={}",
                    tripId, photoType, finding.get().kind(), cost);
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal costOf(DamageKind kind, PhotoType photoType) {
        BigDecimal cost = kind == DamageKind.MISSING_ITEM
                ? damageProperties.missingItemCost()
                : damageProperties.damageCost().get(photoType);
        if (cost == null) {
            throw new IllegalStateException(
                    "app.damage altında maliyet tanımsız: kind=" + kind + ", photoType=" + photoType);
        }
        return cost.setScale(2, RoundingMode.HALF_UP);
    }
}

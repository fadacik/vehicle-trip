package com.yerlem.vehicle_trip.repository;

import com.yerlem.vehicle_trip.entity.OtpCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface OtpCodeRepository extends JpaRepository<OtpCode, Long> {

    /** Kullanicinin en son uretilmis, henuz kullanilmamis OTP'si. */
    Optional<OtpCode> findFirstByUserIdAndUsedFalseOrderByCreatedAtDesc(Long userId);

    /** Yeni OTP uretilirken eski kullanilmamis kodlari gecersiz kilar. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update OtpCode o set o.used = true, o.usedAt = :now where o.user.id = :userId and o.used = false")
    int invalidateUnused(@Param("userId") Long userId, @Param("now") Instant now);
}

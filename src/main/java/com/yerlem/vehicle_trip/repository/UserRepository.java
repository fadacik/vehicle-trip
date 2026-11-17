package com.yerlem.vehicle_trip.repository;

import com.yerlem.vehicle_trip.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByPhone(String phone);

    boolean existsByPhone(String phone);

    /** Ayni telefon icin es zamanli kayit/dogrulama isteklerini sirayla calistirir. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.phone = :phone")
    Optional<User> findByPhoneForUpdate(@Param("phone") String phone);
}

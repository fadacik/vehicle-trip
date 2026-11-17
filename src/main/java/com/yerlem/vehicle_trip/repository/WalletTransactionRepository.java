package com.yerlem.vehicle_trip.repository;

import com.yerlem.vehicle_trip.entity.WalletTransaction;
import com.yerlem.vehicle_trip.enums.WalletTxType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, Long> {
    List<WalletTransaction> findByWalletIdOrderByCreatedAtDesc(Long walletId);
    boolean existsByTripIdAndType(Long tripId, WalletTxType type);
}

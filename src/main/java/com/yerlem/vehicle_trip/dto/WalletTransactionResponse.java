package com.yerlem.vehicle_trip.dto;

import com.yerlem.vehicle_trip.entity.WalletTransaction;
import com.yerlem.vehicle_trip.enums.WalletTxType;

import java.math.BigDecimal;
import java.time.Instant;

/** Cüzdan hareketi. */
public record WalletTransactionResponse(
        Long id,
        WalletTxType type,
        BigDecimal amount,
        BigDecimal balanceAfter,
        String description,
        Long tripId,
        Instant createdAt) {

    public static WalletTransactionResponse from(WalletTransaction tx) {
        return new WalletTransactionResponse(
                tx.getId(),
                tx.getType(),
                tx.getAmount(),
                tx.getBalanceAfter(),
                tx.getDescription(),
                tx.getTrip() != null ? tx.getTrip().getId() : null,
                tx.getCreatedAt()
        );
    }
}

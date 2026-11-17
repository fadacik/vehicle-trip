package com.yerlem.vehicle_trip.dto;

import com.yerlem.vehicle_trip.entity.Wallet;

import java.math.BigDecimal;

/** Cüzdan bilgisi. */
public record WalletResponse(
        Long id,
        Long userId,
        BigDecimal balance) {

    public static WalletResponse from(Wallet wallet) {
        return new WalletResponse(
                wallet.getId(),
                wallet.getUser() != null ? wallet.getUser().getId() : null,
                wallet.getBalance()
        );
    }
}

package com.yerlem.vehicle_trip.controller;

import com.yerlem.vehicle_trip.dto.TopUpRequest;
import com.yerlem.vehicle_trip.dto.WalletResponse;
import com.yerlem.vehicle_trip.dto.WalletTransactionResponse;
import com.yerlem.vehicle_trip.service.WalletService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users/{userId}/wallet")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;

    /** Kullanıcının cüzdan durumunu ve bakiyesini getirir. */
    @GetMapping
    public ResponseEntity<WalletResponse> getWallet(@PathVariable Long userId) {
        return ResponseEntity.ok(walletService.getWallet(userId));
    }

    /** Cüzdana bakiye yükler (Mock ödeme). */
    @PostMapping("/top-up")
    public ResponseEntity<WalletResponse> topUp(
            @PathVariable Long userId,
            @Valid @RequestBody TopUpRequest request) {
        return ResponseEntity.ok(walletService.topUp(userId, request.amount()));
    }

    /** Cüzdan hesap hareketlerini listeler. */
    @GetMapping("/transactions")
    public ResponseEntity<List<WalletTransactionResponse>> getTransactions(@PathVariable Long userId) {
        return ResponseEntity.ok(walletService.getTransactions(userId));
    }
}

package com.yerlem.vehicle_trip.entity;

import com.yerlem.vehicle_trip.enums.DoorAction;
import com.yerlem.vehicle_trip.enums.DoorActionReason;
import com.yerlem.vehicle_trip.enums.DoorActionStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Araç kapısı işlem denetim kaydı (Adım 17 / BLOK A). SUCCESS kaydı ana
 * transaction ile yazılır/geri alınır; FAILED ve COMPENSATION kayıtları
 * ayrı transaction'da (REQUIRES_NEW) kalıcıdır.
 */
@Entity
@Table(name = "trip_door_actions")
@Getter
@Setter
@NoArgsConstructor
public class TripDoorAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vehicle_id", nullable = false)
    private Vehicle vehicle;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false)
    private DoorAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private DoorActionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false)
    private DoorActionReason reason;

    @Column(name = "message", length = 500)
    private String message;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}

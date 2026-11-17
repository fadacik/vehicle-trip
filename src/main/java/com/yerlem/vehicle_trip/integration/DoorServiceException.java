package com.yerlem.vehicle_trip.integration;

/** Kapı servisi çağrısının başarısızlığı (ağ/donanım hatası temsili). */
public class DoorServiceException extends RuntimeException {

    public DoorServiceException(String message) {
        super(message);
    }
}

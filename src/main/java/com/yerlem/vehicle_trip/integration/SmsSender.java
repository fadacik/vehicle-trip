package com.yerlem.vehicle_trip.integration;

/** SMS gonderimi icin soyutlama. Gercek saglayici yerine MockSmsSender kullanilir. */
public interface SmsSender {
    void send(String phone, String message);
}

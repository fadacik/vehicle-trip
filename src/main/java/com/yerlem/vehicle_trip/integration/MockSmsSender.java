package com.yerlem.vehicle_trip.integration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Gelistirme ortami icin sahte SMS servisi: mesaji gondermek yerine loga yazar. */
@Slf4j
@Component
public class MockSmsSender implements SmsSender {

    @Override
    public void send(String phone, String message) {
        log.info("[MOCK SMS] to={} message={}", phone, message);
    }
}

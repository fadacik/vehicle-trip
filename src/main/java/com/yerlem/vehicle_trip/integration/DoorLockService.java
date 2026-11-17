package com.yerlem.vehicle_trip.integration;

/**
 * Araç kapısı kilidi soyutlaması (Adım 17 / D20). Gerçek donanım sağlayıcısı
 * yerine MockDoorLockService kullanılır. Hata durumunda DoorServiceException fırlatılır.
 */
public interface DoorLockService {

    /** Aracın kapısını açar; başarısızlıkta DoorServiceException. */
    void unlock(Long vehicleId);

    /** Aracın kapısını kilitler; başarısızlıkta DoorServiceException. */
    void lock(Long vehicleId);
}

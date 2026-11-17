package com.yerlem.vehicle_trip.service;

/**
 * Exception zincirindeki tüm mesajları null-güvenli biçimde birleştirir. Kısmi
 * unique index ihlallerinin anlamlı iş kuralı hatasına çevrilmesinde kısıt adı
 * bu birleşim içinde aranır.
 */
final class ExceptionMessages {

    private ExceptionMessages() {
    }

    static String collect(Throwable ex) {
        StringBuilder messages = new StringBuilder();
        Throwable current = ex;
        while (current != null) {
            if (current.getMessage() != null) {
                messages.append(current.getMessage()).append(' ');
            }
            current = current.getCause();
        }
        return messages.toString();
    }
}

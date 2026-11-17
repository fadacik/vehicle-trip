package com.yerlem.vehicle_trip.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/** Is kurali ihlallerinde firlatilir; HTTP durumu ve makine tarafindan okunabilir kod tasir. */
@Getter
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}

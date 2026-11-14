package com.aryashah.seatbooking.service;

import org.springframework.http.HttpStatus;

/** A business-rule failure that maps to one HTTP status and a stable machine-readable code. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " not found");
    }

    public static ApiException seatUnavailable(Long seatId) {
        return new ApiException(HttpStatus.CONFLICT, "SEAT_UNAVAILABLE",
                "Seat " + seatId + " is already held or booked");
    }

    public static ApiException holdNotActive() {
        return new ApiException(HttpStatus.CONFLICT, "HOLD_NOT_ACTIVE",
                "Hold has expired, been released or already been confirmed");
    }

    public static ApiException holdNotOwned() {
        return new ApiException(HttpStatus.FORBIDDEN, "HOLD_NOT_OWNED",
                "Hold belongs to a different customer");
    }

    public static ApiException idempotencyKeyReused() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED",
                "Idempotency-Key was already used with a different request body");
    }
}

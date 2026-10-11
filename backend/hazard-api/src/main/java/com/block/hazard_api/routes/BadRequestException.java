package com.block.hazard_api.routes;

/** Something is wrong with what the app sent. Becomes HTTP 400 with {"error": message}. */
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }
}

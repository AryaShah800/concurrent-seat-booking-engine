package com.aryashah.notifications;

/** The record can never be processed (malformed JSON, missing fields). Goes straight to the DLT. */
public class PoisonMessageException extends RuntimeException {
    public PoisonMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.flashsale.order.order;

/** A Kafka record that can never be processed, however often it is retried. */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message) {
        super(message);
    }

    public InvalidEventException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.flashsale.sale.buy;

import org.springframework.http.HttpStatus;

/** Every way a buy attempt can end, with its HTTP status and response body status (ARCHITECTURE.md §9). */
public enum BuyOutcome {
    SUCCESS(HttpStatus.ACCEPTED, "PROCESSING"),
    SOLD_OUT(HttpStatus.GONE, "SOLD_OUT"),
    ALREADY_BOUGHT(HttpStatus.CONFLICT, "ALREADY_BOUGHT"),
    NOT_ACTIVE(HttpStatus.FORBIDDEN, "SALE_NOT_ACTIVE"),
    TRY_AGAIN(HttpStatus.SERVICE_UNAVAILABLE, "TRY_AGAIN"),
    /** The sale has a waiting room and this buyer has not been let in yet. */
    NOT_ADMITTED(HttpStatus.FORBIDDEN, "NOT_ADMITTED");

    private final HttpStatus httpStatus;
    private final String responseStatus;

    BuyOutcome(HttpStatus httpStatus, String responseStatus) {
        this.httpStatus = httpStatus;
        this.responseStatus = responseStatus;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String responseStatus() {
        return responseStatus;
    }

    /** Maps the integer returned by buy.lua. A 1 still has to survive the Kafka send to become SUCCESS. */
    public static BuyOutcome fromScriptResult(long result) {
        if (result == 1) {
            return SUCCESS;
        }
        if (result == 0) {
            return SOLD_OUT;
        }
        if (result == -1) {
            return ALREADY_BOUGHT;
        }
        if (result == -2) {
            return NOT_ACTIVE;
        }
        if (result == -3) {
            return NOT_ADMITTED;
        }
        throw new IllegalStateException("Unexpected buy.lua result: " + result);
    }
}

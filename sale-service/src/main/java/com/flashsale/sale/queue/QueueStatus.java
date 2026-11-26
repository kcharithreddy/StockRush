package com.flashsale.sale.queue;

import com.fasterxml.jackson.annotation.JsonInclude;

/** {"status":"WAITING","position":42} | ADMITTED | NO_WAITING_ROOM | SOLD_OUT | SALE_NOT_ACTIVE | NOT_IN_QUEUE */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QueueStatus(String status, Long position) {

    static QueueStatus of(String status) {
        return new QueueStatus(status, null);
    }
}

package com.flashsale.sale.queue;

import com.flashsale.sale.security.CurrentUser;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** The waiting room API. Both calls need the buyer role and act as the token's subject. */
@RestController
public class QueueController {

    private final WaitingRoomService waitingRoom;

    public QueueController(WaitingRoomService waitingRoom) {
        this.waitingRoom = waitingRoom;
    }

    @PostMapping("/sales/{saleId}/queue")
    public ResponseEntity<QueueStatus> join(@PathVariable @Positive long saleId, @AuthenticationPrincipal Jwt token) {
        return respond(waitingRoom.join(saleId, CurrentUser.id(token)));
    }

    @GetMapping("/sales/{saleId}/queue")
    public ResponseEntity<QueueStatus> status(@PathVariable @Positive long saleId, @AuthenticationPrincipal Jwt token) {
        return respond(waitingRoom.status(saleId, CurrentUser.id(token)));
    }

    private static ResponseEntity<QueueStatus> respond(QueueStatus status) {
        HttpStatus code = switch (status.status()) {
            case "SALE_NOT_ACTIVE" -> HttpStatus.FORBIDDEN;
            case "SOLD_OUT" -> HttpStatus.GONE;
            case "NOT_IN_QUEUE" -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.OK;
        };
        return ResponseEntity.status(code).body(status);
    }
}

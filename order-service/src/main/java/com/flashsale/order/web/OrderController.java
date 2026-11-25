package com.flashsale.order.web;

import com.flashsale.order.order.OrderMetrics;
import com.flashsale.order.order.OrderStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.OptionalLong;
import java.util.UUID;

/**
 * Order status and mock payment (ARCHITECTURE.md §9). Both endpoints only ever see the caller's
 * own orders: another user's token behaves exactly like a token that does not exist.
 */
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderStore orders;
    private final OrderMetrics metrics;

    public OrderController(OrderStore orders, OrderMetrics metrics) {
        this.orders = orders;
        this.metrics = metrics;
    }

    @GetMapping("/{token}")
    public ResponseEntity<OrderStatusResponse> status(
            @PathVariable UUID token,
            @RequestHeader(value = UserIds.HEADER, required = false) String userHeader) {
        OptionalLong userId = UserIds.parse(userHeader);
        if (userId.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String status = orders.findStatus(token, userId.getAsLong()).orElse("PROCESSING");
        return ResponseEntity.ok(new OrderStatusResponse(status));
    }

    @PostMapping("/{token}/pay")
    public ResponseEntity<OrderStatusResponse> pay(
            @PathVariable UUID token,
            @RequestHeader(value = UserIds.HEADER, required = false) String userHeader) {
        OptionalLong userId = UserIds.parse(userHeader);
        if (userId.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (orders.pay(token, userId.getAsLong())) {
            metrics.paid();
            return ResponseEntity.ok(new OrderStatusResponse("PAID"));
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new OrderStatusResponse("NOT_PAYABLE"));
    }
}

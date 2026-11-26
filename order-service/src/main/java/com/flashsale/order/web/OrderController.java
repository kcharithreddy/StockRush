package com.flashsale.order.web;

import com.flashsale.order.order.OrderMetrics;
import com.flashsale.order.order.OrderStore;
import com.flashsale.order.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Order status and mock payment (ARCHITECTURE.md §9). The caller is the bearer token's subject, and both
 * endpoints only ever see the caller's own orders: another user's token behaves exactly like an unknown one.
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
    public OrderStatusResponse status(@PathVariable UUID token, @AuthenticationPrincipal Jwt caller) {
        String status = orders.findStatus(token, CurrentUser.id(caller)).orElse("PROCESSING");
        return new OrderStatusResponse(status);
    }

    @PostMapping("/{token}/pay")
    public ResponseEntity<OrderStatusResponse> pay(@PathVariable UUID token, @AuthenticationPrincipal Jwt caller) {
        if (orders.pay(token, CurrentUser.id(caller))) {
            metrics.paid();
            return ResponseEntity.ok(new OrderStatusResponse("PAID"));
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new OrderStatusResponse("NOT_PAYABLE"));
    }
}

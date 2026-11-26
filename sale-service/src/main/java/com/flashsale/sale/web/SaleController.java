package com.flashsale.sale.web;

import com.flashsale.sale.buy.BuyResult;
import com.flashsale.sale.buy.BuyService;
import com.flashsale.sale.security.CurrentUser;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SaleController {

    private final BuyService buyService;

    public SaleController(BuyService buyService) {
        this.buyService = buyService;
    }

    /** The buyer is whoever the bearer token says it is (SecurityConfig requires the buyer role). */
    @PostMapping("/sales/{saleId}/buy")
    public ResponseEntity<BuyResponse> buy(@PathVariable @Positive long saleId, @AuthenticationPrincipal Jwt token) {
        return toResponse(buyService.buy(saleId, CurrentUser.id(token)));
    }

    static ResponseEntity<BuyResponse> toResponse(BuyResult result) {
        return ResponseEntity.status(result.outcome().httpStatus())
                .body(new BuyResponse(result.outcome().responseStatus(), result.orderToken()));
    }
}

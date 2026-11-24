package com.flashsale.sale.web;

import com.flashsale.sale.buy.BuyResult;
import com.flashsale.sale.buy.BuyService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.OptionalLong;

@RestController
public class SaleController {

    private final BuyService buyService;

    public SaleController(BuyService buyService) {
        this.buyService = buyService;
    }

    @PostMapping("/sales/{saleId}/buy")
    public ResponseEntity<BuyResponse> buy(@PathVariable long saleId,
                                           @RequestHeader(value = UserIds.HEADER, required = false) String userHeader) {
        OptionalLong userId = UserIds.parse(userHeader);
        if (userId.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return toResponse(buyService.buy(saleId, userId.getAsLong()));
    }

    static ResponseEntity<BuyResponse> toResponse(BuyResult result) {
        return ResponseEntity.status(result.outcome().httpStatus())
                .body(new BuyResponse(result.outcome().responseStatus(), result.orderToken()));
    }
}

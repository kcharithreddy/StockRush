package com.flashsale.sale.web;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record BuyResponse(String status, String orderToken) {
}

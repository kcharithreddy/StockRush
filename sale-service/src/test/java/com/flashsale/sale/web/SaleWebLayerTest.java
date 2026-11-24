package com.flashsale.sale.web;

import com.flashsale.sale.buy.BuyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP-level behaviour: validation, Redis outages and error bodies. */
@WebMvcTest(controllers = {SaleController.class, StockController.class})
class SaleWebLayerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    BuyService buyService;

    @MockitoBean
    UserRateLimiter rateLimiter;

    @MockitoBean
    StringRedisTemplate redis;

    @BeforeEach
    void allowAll() throws Exception {
        when(rateLimiter.preHandle(any(), any(), any())).thenReturn(true);
    }

    @Test
    void nonPositiveSaleIdIs400() throws Exception {
        mvc.perform(post("/sales/0/buy").header("X-User-Id", "1")).andExpect(status().isBadRequest());
        mvc.perform(post("/sales/-3/buy").header("X-User-Id", "1")).andExpect(status().isBadRequest());
        mvc.perform(get("/sales/0/stock")).andExpect(status().isBadRequest());
    }

    @Test
    void redisOutageIs503TryAgain() throws Exception {
        when(buyService.buy(anyLong(), anyLong())).thenThrow(new RedisConnectionFailureException("down"));

        mvc.perform(post("/sales/1/buy").header("X-User-Id", "1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.status").value("TRY_AGAIN"));
    }
}

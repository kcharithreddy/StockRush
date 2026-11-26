package com.flashsale.sale.web;

import com.flashsale.sale.buy.BuyResult;
import com.flashsale.sale.buy.BuyService;
import com.flashsale.sale.security.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP-level behaviour: authentication, roles, validation, Redis outages and error bodies. */
@WebMvcTest(controllers = {SaleController.class, StockController.class})
@Import(SecurityConfig.class)
class SaleWebLayerTest {

    private static final UUID USER = UUID.randomUUID();

    @Autowired
    MockMvc mvc;

    @MockitoBean
    BuyService buyService;

    @MockitoBean
    UserRateLimiter rateLimiter;

    @MockitoBean
    StringRedisTemplate redis;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void allowAll() throws Exception {
        when(rateLimiter.preHandle(any(), any(), any())).thenReturn(true);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("sale:1:stock")).thenReturn("7");
    }

    private static RequestPostProcessor buyer() {
        return jwt().jwt(j -> j.subject(USER.toString())).authorities(new SimpleGrantedAuthority("ROLE_buyer"));
    }

    @Test
    void buyerTokenBuysAsTheTokenSubject() throws Exception {
        when(buyService.buy(anyLong(), any())).thenReturn(BuyResult.success("tok"));

        mvc.perform(post("/sales/1/buy").with(buyer()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.orderToken").value("tok"));

        verify(buyService).buy(eq(1L), eq(USER));
    }

    @Test
    void buyWithoutTokenIs401() throws Exception {
        mvc.perform(post("/sales/1/buy")).andExpect(status().isUnauthorized());
        verifyNoInteractions(buyService);
    }

    @Test
    void buyWithoutBuyerRoleIs403() throws Exception {
        mvc.perform(post("/sales/1/buy")
                        .with(jwt().jwt(j -> j.subject(USER.toString())).authorities(new SimpleGrantedAuthority("ROLE_admin"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(buyService);
    }

    @Test
    void stockIsPublicAndOtherPathsAreDenied() throws Exception {
        mvc.perform(get("/sales/1/stock")).andExpect(status().isOk()).andExpect(jsonPath("$.stock").value(7));
        mvc.perform(get("/sales/1/buy").with(buyer())).andExpect(status().isForbidden());
        mvc.perform(get("/anything").with(buyer())).andExpect(status().isForbidden());
    }

    @Test
    void nonPositiveSaleIdIs400() throws Exception {
        mvc.perform(post("/sales/0/buy").with(buyer())).andExpect(status().isBadRequest());
        mvc.perform(post("/sales/-3/buy").with(buyer())).andExpect(status().isBadRequest());
        mvc.perform(get("/sales/0/stock")).andExpect(status().isBadRequest());
    }

    @Test
    void redisOutageIs503TryAgain() throws Exception {
        when(buyService.buy(anyLong(), any())).thenThrow(new RedisConnectionFailureException("down"));

        mvc.perform(post("/sales/1/buy").with(buyer()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.status").value("TRY_AGAIN"));
    }
}

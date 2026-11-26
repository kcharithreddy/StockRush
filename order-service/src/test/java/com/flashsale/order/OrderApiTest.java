package com.flashsale.order;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class OrderApiTest extends IntegrationTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID MALLORY = UUID.randomUUID();

    @Autowired
    MockMvc mvc;

    private static RequestPostProcessor as(UUID user, String role) {
        return jwt().jwt(j -> j.subject(user.toString())).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private static RequestPostProcessor buyer(UUID user) {
        return as(user, "buyer");
    }

    @Test
    void unknownTokenIsProcessing() throws Exception {
        mvc.perform(get("/orders/" + UUID.randomUUID()).with(buyer(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"));
    }

    @Test
    void ownerSeesStatusAndCanPayOnce() throws Exception {
        UUID token = insertOrder(1, ALICE, "PENDING_PAYMENT", 0);

        mvc.perform(get("/orders/" + token).with(buyer(ALICE)))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));
        mvc.perform(post("/orders/" + token + "/pay").with(buyer(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
        mvc.perform(post("/orders/" + token + "/pay").with(buyer(ALICE)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("NOT_PAYABLE"));

        assertThat(statusOf(token)).isEqualTo("PAID");
    }

    @Test
    void otherUsersCannotSeeOrPayAnOrder() throws Exception {
        UUID token = insertOrder(1, ALICE, "PENDING_PAYMENT", 0);

        mvc.perform(get("/orders/" + token).with(buyer(MALLORY)))
                .andExpect(jsonPath("$.status").value("PROCESSING"));
        mvc.perform(post("/orders/" + token + "/pay").with(buyer(MALLORY)))
                .andExpect(status().isConflict());

        assertThat(statusOf(token)).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void cancelledOrderIsNotPayable() throws Exception {
        UUID token = insertOrder(1, ALICE, "CANCELLED", 0);

        mvc.perform(post("/orders/" + token + "/pay").with(buyer(ALICE)))
                .andExpect(status().isConflict());
    }

    @Test
    void ordersNeedABuyerToken() throws Exception {
        UUID token = insertOrder(1, ALICE, "PENDING_PAYMENT", 0);

        mvc.perform(get("/orders/" + token)).andExpect(status().isUnauthorized());
        mvc.perform(post("/orders/" + token + "/pay")).andExpect(status().isUnauthorized());
        mvc.perform(post("/orders/" + token + "/pay").with(as(ALICE, "admin"))).andExpect(status().isForbidden());

        assertThat(statusOf(token)).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void malformedOrderTokenIs400() throws Exception {
        mvc.perform(get("/orders/not-a-uuid").with(buyer(ALICE))).andExpect(status().isBadRequest());
    }

    @Test
    void adminEndpointsNeedTheAdminRole() throws Exception {
        mvc.perform(post("/admin/sales/1/warmup")).andExpect(status().isUnauthorized());
        mvc.perform(post("/admin/sales/1/warmup").with(buyer(ALICE))).andExpect(status().isForbidden());
        mvc.perform(post("/admin/sales/1/reset").with(buyer(ALICE))).andExpect(status().isForbidden());

        mvc.perform(post("/admin/sales/1/warmup").with(as(MALLORY, "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").value(100));
    }

    @Test
    void everythingElseIsDenied() throws Exception {
        mvc.perform(get("/anything").with(as(ALICE, "admin"))).andExpect(status().isForbidden());
    }
}

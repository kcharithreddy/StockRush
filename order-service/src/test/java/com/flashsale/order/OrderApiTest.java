package com.flashsale.order;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class OrderApiTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void unknownTokenIsProcessing() throws Exception {
        mvc.perform(get("/orders/" + UUID.randomUUID()).header("X-User-Id", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"));
    }

    @Test
    void ownerSeesStatusAndCanPayOnce() throws Exception {
        UUID token = insertOrder(1, 42, "PENDING_PAYMENT", 0);

        mvc.perform(get("/orders/" + token).header("X-User-Id", "42"))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));
        mvc.perform(post("/orders/" + token + "/pay").header("X-User-Id", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
        mvc.perform(post("/orders/" + token + "/pay").header("X-User-Id", "42"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("NOT_PAYABLE"));

        assertThat(statusOf(token)).isEqualTo("PAID");
    }

    @Test
    void otherUsersCannotSeeOrPayAnOrder() throws Exception {
        UUID token = insertOrder(1, 42, "PENDING_PAYMENT", 0);

        mvc.perform(get("/orders/" + token).header("X-User-Id", "7"))
                .andExpect(jsonPath("$.status").value("PROCESSING"));
        mvc.perform(post("/orders/" + token + "/pay").header("X-User-Id", "7"))
                .andExpect(status().isConflict());

        assertThat(statusOf(token)).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void cancelledOrderIsNotPayable() throws Exception {
        UUID token = insertOrder(1, 42, "CANCELLED", 0);

        mvc.perform(post("/orders/" + token + "/pay").header("X-User-Id", "42"))
                .andExpect(status().isConflict());
    }

    @Test
    void badInputIsRejected() throws Exception {
        mvc.perform(get("/orders/not-a-uuid").header("X-User-Id", "42")).andExpect(status().isBadRequest());
        mvc.perform(get("/orders/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
        mvc.perform(post("/orders/" + UUID.randomUUID() + "/pay").header("X-User-Id", "-1"))
                .andExpect(status().isUnauthorized());
    }
}

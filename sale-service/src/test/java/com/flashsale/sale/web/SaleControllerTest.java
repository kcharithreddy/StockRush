package com.flashsale.sale.web;

import com.flashsale.sale.buy.BuyOutcome;
import com.flashsale.sale.buy.BuyResult;
import com.flashsale.sale.buy.BuyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Outcome → HTTP mapping, ARCHITECTURE.md §9. */
class SaleControllerTest {

    @ParameterizedTest
    @CsvSource({
            "SOLD_OUT,       410, SOLD_OUT",
            "ALREADY_BOUGHT, 409, ALREADY_BOUGHT",
            "NOT_ACTIVE,     403, SALE_NOT_ACTIVE",
            "TRY_AGAIN,      503, TRY_AGAIN"
    })
    void mapsFailureOutcomes(BuyOutcome outcome, int httpStatus, String bodyStatus) {
        ResponseEntity<BuyResponse> response = SaleController.toResponse(BuyResult.of(outcome));

        assertThat(response.getStatusCode().value()).isEqualTo(httpStatus);
        assertThat(response.getBody()).isEqualTo(new BuyResponse(bodyStatus, null));
    }

    @Test
    void mapsSuccessTo202WithOrderToken() {
        ResponseEntity<BuyResponse> response = SaleController.toResponse(BuyResult.success("tok-1"));

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody()).isEqualTo(new BuyResponse("PROCESSING", "tok-1"));
    }

    @ParameterizedTest
    @CsvSource({"1, SUCCESS", "0, SOLD_OUT", "-1, ALREADY_BOUGHT", "-2, NOT_ACTIVE"})
    void mapsLuaResults(long luaResult, BuyOutcome expected) {
        assertThat(BuyOutcome.fromScriptResult(luaResult)).isEqualTo(expected);
    }

    @Test
    void rejectsUnknownLuaResult() {
        assertThatThrownBy(() -> BuyOutcome.fromScriptResult(7)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void buysAsTheTokenSubject() {
        BuyService buyService = mock(BuyService.class);
        UUID user = UUID.randomUUID();
        when(buyService.buy(anyLong(), any(UUID.class))).thenReturn(BuyResult.success("t"));
        Jwt token = Jwt.withTokenValue("t").header("alg", "none").subject(user.toString()).build();

        new SaleController(buyService).buy(1, token);

        verify(buyService).buy(1, user);
    }
}

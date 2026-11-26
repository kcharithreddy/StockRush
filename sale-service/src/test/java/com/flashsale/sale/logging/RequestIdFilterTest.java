package com.flashsale.sale.logging;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    private String[] run(String incoming) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (incoming != null) {
            request.addHeader(RequestIds.HEADER, incoming);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInMdc = new AtomicReference<>();
        filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seenInMdc.set(MDC.get(RequestIds.MDC_KEY));
            }
        });
        return new String[] {seenInMdc.get(), response.getHeader(RequestIds.HEADER), MDC.get(RequestIds.MDC_KEY)};
    }

    @Test
    void keepsNginxRequestId() throws Exception {
        String[] r = run("7f9c2ba4e88f827d616045507605853e");
        assertThat(r[0]).isEqualTo("7f9c2ba4e88f827d616045507605853e");
        assertThat(r[1]).isEqualTo(r[0]);
        assertThat(r[2]).as("MDC cleared after the request").isNull();
    }

    @Test
    void generatesOneWhenMissing() throws Exception {
        String[] r = run(null);
        assertThat(r[0]).isNotBlank().isEqualTo(r[1]);
    }

    @Test
    void replacesHostileValues() throws Exception {
        String[] r = run("abc\n{\"fake\":\"log line\"}");
        assertThat(r[0]).doesNotContain("fake").hasSize(36);
        assertThat(run("x".repeat(500))[0]).hasSize(36);
    }
}

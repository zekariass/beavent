package com.beavent.backend.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    private String run(String inboundHeader, MockHttpServletResponse response) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/anything");
        if (inboundHeader != null) {
            request.addHeader(RequestIdFilter.HEADER, inboundHeader);
        }
        AtomicReference<String> seenInMdc = new AtomicReference<>();
        filter.doFilter(request, response, (req, res) -> seenInMdc.set(MDC.get(RequestIdFilter.MDC_KEY)));
        assertThat(MDC.get(RequestIdFilter.MDC_KEY))
                .as("MDC cleared after request")
                .isNull();
        return seenInMdc.get();
    }

    @Test
    void reusesWellFormedInboundIds() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(run("edge-123.abc", response)).isEqualTo("edge-123.abc");
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("edge-123.abc");
    }

    @Test
    void replacesMissingOrUnsafeIds() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        String generated = run("bad id\nInjected: header", response);
        assertThat(generated).matches("[0-9a-f-]{36}");
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo(generated);
        assertThat(run(null, new MockHttpServletResponse())).matches("[0-9a-f-]{36}");
    }
}

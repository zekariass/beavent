package com.beavent.backend.platform.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.beavent.backend.IntegrationTest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
@AutoConfigureMockMvc
class ApiSpecEndpointTests {

    @Autowired
    MockMvc mvc;

    @Test
    void servesTheBundledContractPublicly() throws Exception {
        mvc.perform(get("/v1/openapi.yaml"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("openapi: 3.1.0")))
                .andExpect(content().string(Matchers.containsString("operationId: requestOtp")));
    }

    @Test
    void protectedEndpointsRequireAToken() throws Exception {
        mvc.perform(get("/v1/me").header(RequestIdFilter.HEADER, "test-req-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(header().string(RequestIdFilter.HEADER, "test-req-1"));
    }

    @Test
    void unknownRoutesUnderAPublicPrefixReturnProblemJson() throws Exception {
        // /v1/hooks/** is public (provider webhooks), so an unknown hook reaches MVC and gets a problem response.
        mvc.perform(get("/v1/hooks/unknown-provider"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }
}

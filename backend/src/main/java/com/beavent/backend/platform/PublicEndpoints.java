package com.beavent.backend.platform;

import java.util.List;

/**
 * Modules declare which of their endpoints are callable without an access token (sign-in, public reference data,
 * provider webhooks). The platform's security configuration collects all beans of this type.
 */
public interface PublicEndpoints {

    /** Ant-style path patterns, e.g. {@code /v1/auth/otp/**}. */
    List<String> patterns();
}

package com.beavent.backend.identity.internal;

/** Where a sign-in comes from (stored on the session; never an authorization grant). */
public record ClientContext(String clientApp, Device device, String ipAddress, String userAgent) {

    public record Device(String installationId, String platform, String appVersion) {}
}

package com.beavent.backend.identity.internal;

import java.time.Instant;
import java.util.UUID;

public record TokenPair(
        UUID userId,
        UUID sessionFamilyId,
        String accessToken,
        Instant accessTokenExpiresAt,
        String refreshToken,
        Instant refreshTokenExpiresAt) {}

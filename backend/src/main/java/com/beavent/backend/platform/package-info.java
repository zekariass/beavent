/**
 * Platform: cross-cutting infrastructure every other module builds on (docs/03 §2).
 *
 * <p>Public API (this package): {@link com.beavent.backend.platform.RequestContext}, database role qualifiers
 * ({@link com.beavent.backend.platform.SystemDb}, {@link com.beavent.backend.platform.IdentityDb}), the transactional
 * {@link com.beavent.backend.platform.Outbox}, {@link com.beavent.backend.platform.ProcessedMessages},
 * {@link com.beavent.backend.platform.Idempotency}, errors ({@link com.beavent.backend.platform.ApiException},
 * {@link com.beavent.backend.platform.ProblemCode}), cursors and ETags. Sub-packages are internal.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Platform")
package com.beavent.backend.platform;

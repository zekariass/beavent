package com.beavent.backend.platform;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Who is acting, and in which organization's workspace. Every transaction on the application database role starts
 * with {@code platform.set_request_context(userId, orgId)}, so PostgreSQL row-level security sees exactly this
 * context (docs/05 §5). Without a context the application role sees no tenant data: the system fails closed.
 *
 * <p>Set the context <em>before</em> the transaction starts (web filter, message handler, job), never inside it.
 *
 * @param userId authenticated person, or {@code null}
 * @param orgId organization whose workspace or checkout is being used, or {@code null} (consumer context)
 */
public record RequestContext(UUID userId, UUID orgId) {

    private static final ThreadLocal<RequestContext> CURRENT = new ThreadLocal<>();

    /** Partner workspace (or checkout/public page of {@code orgId}). */
    public static RequestContext forOrganization(UUID userId, UUID orgId) {
        return new RequestContext(userId, orgId);
    }

    /** Consumer acting for themselves across organizations. */
    public static RequestContext forUser(UUID userId) {
        return new RequestContext(userId, null);
    }

    /** The context bound to the current thread, or {@code null}. */
    public static RequestContext current() {
        return CURRENT.get();
    }

    /** Runs {@code work} with this context bound, restoring the previous one afterwards. */
    public <T> T call(Supplier<T> work) {
        RequestContext previous = CURRENT.get();
        CURRENT.set(this);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public void run(Runnable work) {
        call(() -> {
            work.run();
            return null;
        });
    }

    /**
     * Binds this context until the returned scope is closed (for filters/interceptors whose work does not fit a
     * lambda). Always close it, e.g. with try-with-resources.
     */
    public Scope open() {
        RequestContext previous = CURRENT.get();
        CURRENT.set(this);
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    /** Restores the previously bound context on close. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}

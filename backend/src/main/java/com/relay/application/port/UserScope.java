package com.relay.application.port;

import java.util.Optional;
import java.util.UUID;

/** Carries the authenticated user across request and application worker threads. */
public final class UserScope {

    private final InheritableThreadLocal<UUID> current = new InheritableThreadLocal<>();

    public Optional<UUID> currentUserId() {
        return Optional.ofNullable(current.get());
    }

    public UUID requireUserId() {
        return currentUserId().orElseThrow(() ->
                new IllegalStateException("An authenticated user is required for this operation"));
    }

    public Scope enter(UUID userId) {
        UUID previous = current.get();
        current.set(userId);
        return () -> {
            if (previous == null) {
                current.remove();
            } else {
                current.set(previous);
            }
        };
    }

    public Runnable capture(Runnable task) {
        UUID userId = current.get();
        return () -> {
            UUID previous = current.get();
            if (userId == null) current.remove(); else current.set(userId);
            try {
                task.run();
            } finally {
                if (previous == null) current.remove(); else current.set(previous);
            }
        };
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}

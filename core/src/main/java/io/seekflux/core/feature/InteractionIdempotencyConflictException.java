package io.seekflux.core.feature;

public final class InteractionIdempotencyConflictException extends RuntimeException {

    public InteractionIdempotencyConflictException(String idempotencyKey) {
        super("idempotency key was already used with a different interaction batch: " + idempotencyKey);
    }
}

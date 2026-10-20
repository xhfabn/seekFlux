package io.seekflux.agent.domain;

public final class ConstraintVersionConflictException extends RuntimeException {

    public ConstraintVersionConflictException(long requested, long current) {
        super("constraint patch base version " + requested + " does not match current version " + current);
    }
}

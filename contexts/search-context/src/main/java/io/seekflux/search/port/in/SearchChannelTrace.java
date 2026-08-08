package io.seekflux.search.port.in;

import java.util.Objects;

public record SearchChannelTrace(
        String source,
        String status,
        String retrieverVersion,
        long tookMillis,
        int candidateCount,
        String errorCode) {

    public SearchChannelTrace {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(status, "status must not be null");
        retrieverVersion = retrieverVersion == null ? "unavailable" : retrieverVersion;
        if (tookMillis < 0 || candidateCount < 0) {
            throw new IllegalArgumentException("channel timing and candidate count must not be negative");
        }
    }
}

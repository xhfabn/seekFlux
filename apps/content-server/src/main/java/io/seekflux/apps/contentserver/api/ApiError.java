package io.seekflux.apps.contentserver.api;

import java.time.Instant;

public record ApiError(String code, String message, Instant timestamp) {
}

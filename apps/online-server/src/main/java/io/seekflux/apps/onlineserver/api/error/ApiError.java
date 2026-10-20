package io.seekflux.apps.onlineserver.api.error;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;

public record ApiError(String code, String message, @JsonFormat(shape = JsonFormat.Shape.STRING) Instant timestamp) {
}

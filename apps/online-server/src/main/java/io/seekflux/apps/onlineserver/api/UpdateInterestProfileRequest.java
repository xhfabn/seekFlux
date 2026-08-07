package io.seekflux.apps.onlineserver.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record UpdateInterestProfileRequest(
        @NotNull @Size(max = 20) List<@NotBlank @Size(max = 64) String> topics) {
}

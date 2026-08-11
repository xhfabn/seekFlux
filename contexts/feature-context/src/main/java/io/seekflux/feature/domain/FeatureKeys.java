package io.seekflux.feature.domain;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

public final class FeatureKeys {

    public static final String SHORT_INTEREST_PREFIX = "seekflux:feature:short-interest:";
    public static final String CONTENT_HEAT_PREFIX = "seekflux:feature:content-heat:";

    private FeatureKeys() {
    }

    public static String shortInterest(String userId) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(userId.trim().getBytes(StandardCharsets.UTF_8));
        return SHORT_INTEREST_PREFIX + encoded;
    }

    public static String contentHeat(UUID contentId) {
        return CONTENT_HEAT_PREFIX + contentId;
    }
}

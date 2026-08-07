package io.seekflux.apps.onlineserver.api;

import io.seekflux.userinterest.domain.InterestProfile;
import java.time.Instant;
import java.util.List;

public record UserInterestResponse(String userId, List<String> topics, Instant updatedAt) {

    static UserInterestResponse from(InterestProfile profile) {
        return new UserInterestResponse(profile.userId(), profile.topics(), profile.updatedAt());
    }
}

package io.seekflux.userinterest.application;

import io.seekflux.userinterest.domain.InterestProfile;
import io.seekflux.userinterest.port.in.UserInterestUseCase;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

public final class ExplicitInterestService implements UserInterestUseCase {

    private final Clock clock;

    public ExplicitInterestService(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public InterestProfile resolve(String userId, List<String> explicitTopics) {
        return new InterestProfile(userId, explicitTopics, clock.instant());
    }
}

package io.seekflux.userinterest.application;

import io.seekflux.userinterest.domain.InterestProfile;
import io.seekflux.userinterest.port.in.UserInterestUseCase;
import io.seekflux.userinterest.port.out.UserInterestRepository;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

public final class ExplicitInterestService implements UserInterestUseCase {

    private final Clock clock;
    private final UserInterestRepository repository;

    public ExplicitInterestService(Clock clock, UserInterestRepository repository) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    @Override
    public InterestProfile resolve(String userId, List<String> explicitTopics) {
        InterestProfile explicit = new InterestProfile(userId, explicitTopics, clock.instant());
        if (!explicit.topics().isEmpty()) {
            return explicit;
        }
        return repository.findByUserId(explicit.userId()).orElse(explicit);
    }

    @Override
    public InterestProfile save(String userId, List<String> topics) {
        InterestProfile profile = new InterestProfile(userId, topics, clock.instant());
        repository.save(profile);
        return profile;
    }
}

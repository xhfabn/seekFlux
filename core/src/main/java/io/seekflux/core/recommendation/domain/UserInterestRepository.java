package io.seekflux.core.recommendation.domain;

import io.seekflux.core.recommendation.domain.InterestProfile;
import java.util.Optional;

public interface UserInterestRepository {

    Optional<InterestProfile> findByUserId(String userId);

    void save(InterestProfile profile);
}

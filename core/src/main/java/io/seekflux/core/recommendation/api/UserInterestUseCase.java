package io.seekflux.core.recommendation.api;

import io.seekflux.core.recommendation.domain.InterestProfile;
import java.util.List;

public interface UserInterestUseCase {

    InterestProfile resolve(String userId, List<String> explicitTopics);

    InterestProfile save(String userId, List<String> topics);
}

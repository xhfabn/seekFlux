package io.seekflux.userinterest.port.in;

import io.seekflux.userinterest.domain.InterestProfile;
import java.util.List;

public interface UserInterestUseCase {

    InterestProfile resolve(String userId, List<String> explicitTopics);

    InterestProfile save(String userId, List<String> topics);
}

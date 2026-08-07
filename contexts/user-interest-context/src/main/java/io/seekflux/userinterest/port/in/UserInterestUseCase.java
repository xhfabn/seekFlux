package io.seekflux.userinterest.port.in;

import io.seekflux.userinterest.domain.InterestProfile;
import java.util.List;
import reactor.core.publisher.Mono;

public interface UserInterestUseCase {

    Mono<InterestProfile> resolve(String userId, List<String> explicitTopics);

    Mono<InterestProfile> save(String userId, List<String> topics);
}

package io.seekflux.userinterest.port.out;

import io.seekflux.userinterest.domain.InterestProfile;
import reactor.core.publisher.Mono;

public interface UserInterestRepository {

    Mono<InterestProfile> findByUserId(String userId);

    Mono<Void> save(InterestProfile profile);
}

package io.seekflux.userinterest.port.out;

import io.seekflux.userinterest.domain.InterestProfile;
import java.util.Optional;

public interface UserInterestRepository {

    Optional<InterestProfile> findByUserId(String userId);

    void save(InterestProfile profile);
}

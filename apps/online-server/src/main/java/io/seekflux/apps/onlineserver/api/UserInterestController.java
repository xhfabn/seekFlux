package io.seekflux.apps.onlineserver.api;

import io.seekflux.userinterest.port.in.UserInterestUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/v1/users/{userId}/interest-profile")
public class UserInterestController {

    private final UserInterestUseCase userInterest;

    public UserInterestController(UserInterestUseCase userInterest) {
        this.userInterest = userInterest;
    }

    @GetMapping
    public UserInterestResponse get(
            @PathVariable("userId") @Size(min = 1, max = 128) String userId) {
        return UserInterestResponse.from(userInterest.resolve(userId, List.of()));
    }

    @PutMapping
    public UserInterestResponse save(
            @PathVariable("userId") @Size(min = 1, max = 128) String userId,
            @Valid @RequestBody UpdateInterestProfileRequest request) {
        return UserInterestResponse.from(userInterest.save(userId, request.topics()));
    }
}

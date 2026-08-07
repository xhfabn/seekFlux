package io.seekflux.apps.onlineserver.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.seekflux.userinterest.domain.InterestProfile;
import io.seekflux.userinterest.port.in.UserInterestUseCase;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class UserInterestControllerTest {

    @Test
    void savesTheProfileUsedByRecommendation() throws Exception {
        AtomicReference<InterestProfile> stored = new AtomicReference<>();
        UserInterestUseCase useCase = new UserInterestUseCase() {
            @Override
            public InterestProfile resolve(String userId, List<String> explicitTopics) {
                return stored.get();
            }

            @Override
            public InterestProfile save(String userId, List<String> topics) {
                InterestProfile profile = new InterestProfile(
                        userId, topics, Instant.parse("2026-08-08T10:00:00Z"));
                stored.set(profile);
                return profile;
            }
        };
        MockMvc client = MockMvcBuilders
                .standaloneSetup(new UserInterestController(useCase))
                .build();

        client.perform(put("/v1/users/demo-user/interest-profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topics\":[\"露营\",\"摄影\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("demo-user"))
                .andExpect(jsonPath("$.topics[0]").value("露营"))
                .andExpect(jsonPath("$.topics[1]").value("摄影"));

        assertEquals(List.of("露营", "摄影"), stored.get().topics());
    }
}

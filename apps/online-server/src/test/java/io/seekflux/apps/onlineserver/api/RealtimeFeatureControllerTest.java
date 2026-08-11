package io.seekflux.apps.onlineserver.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.seekflux.feature.application.RealtimeFeaturePolicy;
import io.seekflux.feature.domain.ContentHeatSnapshot;
import io.seekflux.feature.domain.FeatureRead;
import io.seekflux.feature.domain.FeatureTopicScore;
import io.seekflux.feature.domain.ShortTermInterestSnapshot;
import io.seekflux.feature.port.in.RealtimeFeatureUseCase;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RealtimeFeatureControllerTest {

    @Test
    void returnsVersionedFreshShortTermInterest() throws Exception {
        Instant now = Instant.parse("2026-08-11T10:00:00Z");
        RealtimeFeatureUseCase useCase = new RealtimeFeatureUseCase() {
            @Override
            public FeatureRead<ShortTermInterestSnapshot> shortTermInterest(String userId) {
                return FeatureRead.fresh(new ShortTermInterestSnapshot(
                        userId, List.of(new FeatureTopicScore("露营", 3.0)),
                        now.minusSeconds(1800), now, now, RealtimeFeaturePolicy.FEATURE_VERSION));
            }

            @Override
            public Map<UUID, FeatureRead<ContentHeatSnapshot>> contentHeat(Iterable<UUID> contentIds) {
                return Map.of();
            }
        };
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new RealtimeFeatureController(useCase)).build();

        mvc.perform(get("/v1/features/users/demo-user/short-term-interest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FRESH"))
                .andExpect(jsonPath("$.topics[0].topic").value("露营"))
                .andExpect(jsonPath("$.featureVersion").value(RealtimeFeaturePolicy.FEATURE_VERSION));
    }
}

package io.seekflux.feature.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seekflux.feature.domain.RealtimeFeatureEvent;
import io.seekflux.interaction.domain.InteractionType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RealtimeFeaturePolicyTest {

    private static final Instant NOW = Instant.parse("2026-08-11T10:00:00Z");
    private static final UUID CONTENT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final RealtimeFeaturePolicy policy = new RealtimeFeaturePolicy();

    @Test
    void ranksPositiveRecentTopicsAndLetsNegativeFeedbackRemoveOne() {
        var snapshot = policy.shortTermInterest("u1", List.of(
                event("1", InteractionType.LIKE, List.of("露营"), NOW.minusSeconds(60)),
                event("2", InteractionType.SAVE, List.of("咖啡"), NOW.minusSeconds(30)),
                event("3", InteractionType.NOT_INTERESTED, List.of("露营"), NOW.minusSeconds(10))), NOW, NOW);

        assertEquals(List.of("咖啡"), snapshot.topics().stream().map(topic -> topic.topic()).toList());
        assertEquals(RealtimeFeaturePolicy.FEATURE_VERSION, snapshot.featureVersion());
    }

    @Test
    void enforcesWatermarkAndAllowedLateness() {
        Instant max = NOW;
        assertFalse(policy.tooLate(NOW.minusSeconds(34), max));
        assertTrue(policy.tooLate(NOW.minusSeconds(36), max));
        assertEquals(NOW.minusSeconds(5), policy.watermark(max));
    }

    @Test
    void heatIsDeterministicForReplayOrder() {
        List<RealtimeFeatureEvent> ordered = List.of(
                event("1", InteractionType.EXPOSURE, List.of("露营"), NOW.minusSeconds(5)),
                event("2", InteractionType.LIKE, List.of("露营"), NOW.minusSeconds(4)));
        List<RealtimeFeatureEvent> reversed = List.of(ordered.get(1), ordered.get(0));

        assertEquals(
                policy.contentHeat(CONTENT, ordered, NOW, NOW),
                policy.contentHeat(CONTENT, reversed, NOW, NOW));
    }

    private static RealtimeFeatureEvent event(
            String suffix, InteractionType type, List<String> tags, Instant eventTime) {
        return new RealtimeFeatureEvent(
                UUID.fromString("00000000-0000-0000-0000-00000000000" + suffix),
                "u1", type, CONTENT, tags, eventTime, NOW);
    }
}

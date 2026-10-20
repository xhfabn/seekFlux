package io.seekflux.feature.application;

import io.seekflux.feature.domain.ContentHeatSnapshot;
import io.seekflux.feature.domain.FeatureTopicScore;
import io.seekflux.feature.domain.RealtimeFeatureEvent;
import io.seekflux.feature.domain.ShortTermInterestSnapshot;
import io.seekflux.interaction.domain.InteractionType;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class RealtimeFeaturePolicy {

    public static final String FEATURE_VERSION = "realtime-window-v1";
    public static final Duration OUT_OF_ORDER_BOUND = Duration.ofSeconds(5);
    public static final Duration ALLOWED_LATENESS = Duration.ofSeconds(30);
    public static final Duration SHORT_INTEREST_WINDOW = Duration.ofMinutes(30);
    public static final Duration CONTENT_HEAT_WINDOW = Duration.ofMinutes(5);
    public static final Duration MAX_FEATURE_AGE = Duration.ofSeconds(30);
    public static final Duration ONLINE_TTL = Duration.ofHours(2);

    private static final double LN_2 = Math.log(2.0);
    private static final Duration INTEREST_HALF_LIFE = Duration.ofMinutes(10);
    private static final Duration HEAT_HALF_LIFE = Duration.ofMinutes(2);
    private static final Map<InteractionType, Double> WEIGHTS = weights();

    public ShortTermInterestSnapshot shortTermInterest(
            String userId,
            List<RealtimeFeatureEvent> events,
            Instant windowEnd,
            Instant computedAt) {
        Instant windowStart = windowEnd.minus(SHORT_INTEREST_WINDOW);
        Map<String, Double> scores = new LinkedHashMap<>();
        for (RealtimeFeatureEvent event : events) {
            if (!event.userId().equals(userId) || event.eventTime().isBefore(windowStart)
                    || event.eventTime().isAfter(windowEnd)) {
                continue;
            }
            double contribution = weight(event.eventType())
                    * decay(Duration.between(event.eventTime(), windowEnd), INTEREST_HALF_LIFE);
            for (String tag : event.contentTags()) {
                scores.merge(tag, contribution, Double::sum);
            }
        }
        List<FeatureTopicScore> topics = scores.entrySet().stream()
                .filter(entry -> entry.getValue() > 0.000001)
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry::getKey))
                .limit(10)
                .map(entry -> new FeatureTopicScore(entry.getKey(), round(entry.getValue())))
                .toList();
        return new ShortTermInterestSnapshot(
                userId, topics, windowStart, windowEnd, computedAt, FEATURE_VERSION);
    }

    public ContentHeatSnapshot contentHeat(
            UUID contentId,
            List<RealtimeFeatureEvent> events,
            Instant windowEnd,
            Instant computedAt) {
        Instant windowStart = windowEnd.minus(CONTENT_HEAT_WINDOW);
        double score = 0;
        long count = 0;
        for (RealtimeFeatureEvent event : events) {
            if (!event.contentId().equals(contentId) || event.eventTime().isBefore(windowStart)
                    || event.eventTime().isAfter(windowEnd)) {
                continue;
            }
            score += heatWeight(event.eventType())
                    * decay(Duration.between(event.eventTime(), windowEnd), HEAT_HALF_LIFE);
            count++;
        }
        return new ContentHeatSnapshot(
                contentId, round(score), count, windowStart, windowEnd, computedAt, FEATURE_VERSION);
    }

    public boolean tooLate(Instant eventTime, Instant currentMaxEventTime) {
        Instant watermark = currentMaxEventTime.minus(OUT_OF_ORDER_BOUND);
        return eventTime.isBefore(watermark.minus(ALLOWED_LATENESS));
    }

    public Instant watermark(Instant maxEventTime) {
        return maxEventTime.minus(OUT_OF_ORDER_BOUND);
    }

    public static double weight(InteractionType type) {
        return WEIGHTS.get(type);
    }

    private static double heatWeight(InteractionType type) {
        return switch (type) {
            case EXPOSURE -> 0.05;
            case NOT_INTERESTED -> -2.0;
            default -> Math.max(0, weight(type));
        };
    }

    private static double decay(Duration age, Duration halfLife) {
        double seconds = Math.max(0, age.toMillis() / 1000.0);
        return Math.exp(-LN_2 * seconds / halfLife.toSeconds());
    }

    private static double round(double value) {
        return Math.rint(value * 1_000_000.0) / 1_000_000.0;
    }

    private static Map<InteractionType, Double> weights() {
        EnumMap<InteractionType, Double> weights = new EnumMap<>(InteractionType.class);
        weights.put(InteractionType.EXPOSURE, 0.0);
        weights.put(InteractionType.CLICK, 0.5);
        weights.put(InteractionType.PLAY_START, 0.8);
        weights.put(InteractionType.LIKE, 3.0);
        weights.put(InteractionType.SAVE, 4.0);
        weights.put(InteractionType.PLAY_COMPLETE, 5.0);
        weights.put(InteractionType.NOT_INTERESTED, -6.0);
        return Map.copyOf(weights);
    }
}

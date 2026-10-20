package io.seekflux.core.recommendation.api;

import io.seekflux.core.recommendation.api.RankedCandidate;
import io.seekflux.core.recommendation.api.RankingCandidate;
import io.seekflux.core.recommendation.api.RankingRequest;
import java.util.List;

public interface RankingUseCase {

    List<RankedCandidate> rank(List<RankingCandidate> candidates, RankingRequest request);
}

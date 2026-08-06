package io.seekflux.ranking.port.in;

import io.seekflux.ranking.domain.RankedCandidate;
import io.seekflux.ranking.domain.RankingCandidate;
import io.seekflux.ranking.domain.RankingRequest;
import java.util.List;

public interface RankingUseCase {

    List<RankedCandidate> rank(List<RankingCandidate> candidates, RankingRequest request);
}

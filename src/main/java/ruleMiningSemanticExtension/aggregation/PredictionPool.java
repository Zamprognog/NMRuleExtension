package ruleMiningSemanticExtension.aggregation;

import ruleMiningSemanticExtension.domain.PredictionCandidate;
import java.util.*;

public class PredictionPool {
    private final Map<String, PredictionCandidate> predictions;

    public PredictionPool(Map<String, PredictionCandidate> predictions) {
        this.predictions = predictions;
    }

    /**
     * Ranks the candidates in the pool using the provided aggregator.
     *
     * @param aggregator The aggregator to use for scoring candidates.
     * @return A list of scored candidates, sorted by score in descending order.
     */
    public List<ScoredCandidate> getRankedCandidates(PredictionAggregator aggregator) {
        List<ScoredCandidate> scoredCandidates = new ArrayList<>();
        for (PredictionCandidate pc : predictions.values()) {
            double score = aggregator.aggregate(pc);
            scoredCandidates.add(new ScoredCandidate(pc, score));
        }
        scoredCandidates.sort(Comparator.comparingDouble(ScoredCandidate::score).reversed()
                .thenComparing(ScoredCandidate::getEntity));
        return scoredCandidates;
    }

    /**
     * Represents a candidate with its aggregated score.
     */
    public Collection<PredictionCandidate> getCandidates() {
        return predictions.values();
    }

    public record ScoredCandidate(PredictionCandidate candidate, double score) {
        public String getEntity() {
            return candidate.getEntity();
        }
    }
}

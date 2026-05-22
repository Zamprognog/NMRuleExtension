package ruleMiningSemanticExtension.aggregation;

import ruleMiningSemanticExtension.domain.PredictionCandidate;

/**
 * Interface for aggregating the results of multiple rule groundings for a single prediction candidate.
 * This can be used to produce a single score for ranking candidates.
 */
public interface PredictionAggregator {
    /**
     * Aggregates the groundings and confidences of a prediction candidate into a single score.
     *
     * @param candidate The prediction candidate containing groundings and rules.
     * @return An aggregated score.
     */
    double aggregate(PredictionCandidate candidate);
}

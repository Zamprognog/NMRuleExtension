package ruleMiningSemanticExtension.aggregation;

import ruleMiningSemanticExtension.domain.PredictionCandidate;

/**
 * An aggregator that simply picks the maximum confidence among all rules
 * that predicted the candidate.
 */
public class MaxAggregator implements PredictionAggregator {
    @Override
    public double aggregate(PredictionCandidate candidate) {
        return candidate.getConfidences().stream()
                .mapToDouble(Float::doubleValue)
                .max()
                .orElse(0.0);
    }
}

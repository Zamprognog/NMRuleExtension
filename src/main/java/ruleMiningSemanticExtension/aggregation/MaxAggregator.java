package ruleMiningSemanticExtension.aggregation;

import ruleMiningSemanticExtension.domain.PredictionCandidate;

/**
 * An aggregator that simply picks the maximum confidence among all rules
 * that predicted the candidate.
 */
public class MaxAggregator implements PredictionAggregator {
    @Override
    public double aggregate(PredictionCandidate candidate) {
        float[] confs = candidate.getConfidences();
        float max = 0f;
        for (float c : confs) if (c > max) max = c;
        return max;
    }
}

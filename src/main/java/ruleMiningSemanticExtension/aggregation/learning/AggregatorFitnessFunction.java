package ruleMiningSemanticExtension.aggregation.learning;

import io.jenetics.Gene;
import io.jenetics.Genotype;
import ruleMiningSemanticExtension.aggregation.PredictionPool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class AggregatorFitnessFunction<G extends Gene<?, G>> {

    private final List<ValidationQuery> validationSet;
    private final EvolvableAggregator<G> aggregatorPrototype;
    private final int batchSize;

    /**
     * @param batchSize number of queries sampled per fitness call (0 = use all).
     *                  Re-sampled on every call, so each individual sees a different subset.
     */
    public AggregatorFitnessFunction(
            List<ValidationQuery> validationSet,
            EvolvableAggregator<G> aggregatorPrototype,
            int batchSize) {
        this.validationSet = validationSet;
        this.aggregatorPrototype = aggregatorPrototype;
        this.batchSize = batchSize;
    }

    public double evaluate(Genotype<G> genotype) {
        if (validationSet.isEmpty()) return 0.0;

        EvolvableAggregator<G> aggregator = aggregatorPrototype.newInstance();
        aggregator.configure(genotype);

        List<ValidationQuery> batch = selectBatch();
        double totalMetric = 0.0;
        for (ValidationQuery query : batch) {
            totalMetric += scoreQuery(aggregator, query);
        }
        return totalMetric / batch.size();
    }

    private double scoreQuery(EvolvableAggregator<G> aggregator, ValidationQuery query) {
        double correctScore = Double.NEGATIVE_INFINITY;
        for (CandidateEntry e : query.candidates()) {
            if (e.entity().equals(query.correctEntity())) {
                correctScore = aggregator.scoreFeatures(e.features());
                break;
            }
        }
        if (correctScore == Double.NEGATIVE_INFINITY) return 0.0;

        // Pessimistic tie-breaking: correct entity is ranked last among tied candidates.
        // Prevents constant-output trees (e.g. weight-only nodes) from scoring 1.0 on every query.
        int rank = 1;
        int ties = 0;
        for (CandidateEntry e : query.candidates()) {
            if (e.entity().equals(query.correctEntity())) continue;
            double s = aggregator.scoreFeatures(e.features());
            if (s > correctScore)       rank++;
            else if (s == correctScore) ties++;
        }
        return 1.0 / (rank + ties);
    }

    /** Partial Fisher-Yates: O(batchSize) sampling without full list copy. */
    private List<ValidationQuery> selectBatch() {
        int n = validationSet.size();
        if (batchSize <= 0 || batchSize >= n) return validationSet;

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int[] indices = new int[n];
        for (int i = 0; i < n; i++) indices[i] = i;

        List<ValidationQuery> batch = new ArrayList<>(batchSize);
        for (int i = 0; i < batchSize; i++) {
            int j = rng.nextInt(i, n);
            int tmp = indices[i]; indices[i] = indices[j]; indices[j] = tmp;
            batch.add(validationSet.get(indices[i]));
        }
        return batch;
    }

    /** Pre-computed features for one candidate. Immutable after construction. */
    public record CandidateEntry(String entity, Double[] features) {}

    /**
     * A validation query backed by pre-extracted feature arrays.
     * Use the factory method to build from a PredictionPool.
     */
    public record ValidationQuery(String queryInfo, List<CandidateEntry> candidates, String correctEntity) {

        public static ValidationQuery from(String queryInfo, PredictionPool pool, String correctEntity) {
            List<CandidateEntry> entries = pool.getCandidates().stream()
                    .map(c -> new CandidateEntry(c.getEntity(), FeatureExtractor.extract(c)))
                    .toList();
            return new ValidationQuery(queryInfo, entries, correctEntity);
        }
    }
}

package ruleMiningSemanticExtension.aggregation.learning;

import io.jenetics.Gene;
import io.jenetics.Genotype;
import ruleMiningSemanticExtension.aggregation.PredictionPool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
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
        // Fix 5: O(1) lookup via pre-indexed position — no linear scan for correct entity.
        int ci = query.correctIndex();
        if (ci < 0) return 0.0;
        double correctScore = aggregator.scoreFeatures(query.candidates().get(ci).features());

        // Filtered rank: skip candidates that are known true answers for this query.
        // Expected MRR: average over the tie block [rank, rank+ties], matching eval protocol.
        int rank = 1;
        int ties = 0;
        List<CandidateEntry> candidates = query.candidates();
        for (int i = 0; i < candidates.size(); i++) {
            if (i == ci) continue;
            CandidateEntry e = candidates.get(i);
            if (query.filteredEntities().contains(e.entity())) continue;
            double s = aggregator.scoreFeatures(e.features());
            if (s > correctScore)       rank++;
            else if (s == correctScore) ties++;
        }
        if (ties == 0) return 1.0 / rank;
        double mrrSum = 0;
        for (int r = rank; r <= rank + ties; r++) mrrSum += 1.0 / r;
        return mrrSum / (ties + 1);
    }

    /**
     * Fix 1: Knuth's Algorithm S — reservoir sampling without allocating an int[n] array.
     * O(n) sequential scan, zero heap allocations beyond the result list itself.
     * Thread-safe: uses only ThreadLocalRandom and local variables.
     */
    private List<ValidationQuery> selectBatch() {
        int n = validationSet.size();
        if (batchSize <= 0 || batchSize >= n) return validationSet;

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        List<ValidationQuery> batch = new ArrayList<>(batchSize);
        int needed = batchSize;
        for (int i = 0; i < n && needed > 0; i++) {
            // Include element i with probability needed / (n - i)
            if (rng.nextInt(n - i) < needed) {
                batch.add(validationSet.get(i));
                needed--;
            }
        }
        return batch;
    }

    /** Pre-computed features for one candidate. Immutable after construction. */
    // Fix 2: double[] instead of Double[] — eliminates boxing overhead in the hot GP loop.
    public record CandidateEntry(String entity, double[] features) {}

    /**
     * A validation query backed by pre-extracted feature arrays.
     * {@code filteredEntities} contains known true answers for this query (excluding the
     * correct entity) that should be skipped when computing rank.
     * {@code correctIndex} is the index of the correct entity in {@code candidates}, or -1
     * if it is absent (Fix 5: avoids a linear scan per fitness call).
     */
    public record ValidationQuery(String queryInfo, List<CandidateEntry> candidates,
                                  String correctEntity, Set<String> filteredEntities,
                                  int correctIndex) {

        public static ValidationQuery from(String queryInfo, PredictionPool pool,
                                           String correctEntity, Set<String> filteredEntities) {
            List<CandidateEntry> entries = pool.getCandidates().stream()
                    .map(c -> new CandidateEntry(c.getEntity(), FeatureExtractor.extract(c)))
                    .toList();
            // Fix 5: find correct entity index once at construction time.
            int idx = -1;
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).entity().equals(correctEntity)) { idx = i; break; }
            }
            return new ValidationQuery(queryInfo, entries, correctEntity, filteredEntities, idx);
        }

        /** Convenience overload for tests / callers without filtering information. */
        public static ValidationQuery from(String queryInfo, PredictionPool pool, String correctEntity) {
            return from(queryInfo, pool, correctEntity, Collections.emptySet());
        }
    }
}

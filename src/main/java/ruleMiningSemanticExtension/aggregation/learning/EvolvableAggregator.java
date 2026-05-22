package ruleMiningSemanticExtension.aggregation.learning;

import io.jenetics.Gene;
import io.jenetics.Genotype;
import io.jenetics.util.Factory;
import ruleMiningSemanticExtension.aggregation.PredictionAggregator;

public interface EvolvableAggregator<G extends Gene<?, G>> extends PredictionAggregator {

    void configure(Genotype<G> genotype);

    Factory<Genotype<G>> getGenotypeFactory();

    /**
     * Scores a candidate from pre-computed feature array, bypassing PredictionCandidate
     * construction and feature extraction. Used by the fitness function during evolution.
     */
    double scoreFeatures(Double[] features);

    /**
     * Returns a fresh unconfigured instance of the same aggregator type.
     * Used instead of reflection for thread-safe parallel fitness evaluation.
     */
    EvolvableAggregator<G> newInstance();
}

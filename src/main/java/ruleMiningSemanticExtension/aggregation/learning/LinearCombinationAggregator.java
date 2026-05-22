package ruleMiningSemanticExtension.aggregation.learning;

import io.jenetics.DoubleChromosome;
import io.jenetics.DoubleGene;
import io.jenetics.Genotype;
import io.jenetics.util.Factory;
import ruleMiningSemanticExtension.domain.PredictionCandidate;

public class LinearCombinationAggregator implements EvolvableAggregator<DoubleGene> {

    private double[] weights = new double[FeatureExtractor.FEATURE_COUNT];

    @Override
    public void configure(Genotype<DoubleGene> genotype) {
        var chromosome = genotype.chromosome();
        for (int i = 0; i < weights.length && i < chromosome.length(); i++) {
            weights[i] = chromosome.get(i).doubleValue();
        }
    }

    @Override
    public Factory<Genotype<DoubleGene>> getGenotypeFactory() {
        return Genotype.of(DoubleChromosome.of(0.0, 1.0, weights.length));
    }

    @Override
    public double scoreFeatures(Double[] features) {
        double score = 0.0;
        for (int i = 0; i < weights.length; i++) {
            score += weights[i] * features[i];
        }
        return score;
    }

    @Override
    public double aggregate(PredictionCandidate candidate) {
        return scoreFeatures(FeatureExtractor.extract(candidate));
    }

    @Override
    public EvolvableAggregator<DoubleGene> newInstance() {
        return new LinearCombinationAggregator();
    }

    public double[] getWeights() {
        return weights;
    }
}

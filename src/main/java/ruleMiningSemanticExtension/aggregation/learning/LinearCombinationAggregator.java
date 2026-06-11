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
    public double scoreFeatures(double[] features) {
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

    /** Returns the learned formula as a weighted sum, e.g. {@code 0.831·max + 0.412·noisyOr + ...}.
     *  Terms with weight &lt; 0.001 are omitted and counted at the end. */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        int omitted = 0;
        for (int i = 0; i < weights.length; i++) {
            if (Math.abs(weights[i]) < 1e-3) { omitted++; continue; }
            if (sb.length() > 0) sb.append(" + ");
            sb.append(String.format("%.4f·%s", weights[i], FeatureExtractor.FEATURE_NAMES[i]));
        }
        if (sb.length() == 0) return "0 (all weights negligible)";
        if (omitted > 0) sb.append(String.format(" [+%d negligible]", omitted));
        return sb.toString();
    }
}

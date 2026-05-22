package ruleMiningSemanticExtension.aggregation.learning;

import io.jenetics.Chromosome;
import io.jenetics.DoubleChromosome;
import io.jenetics.Genotype;
import io.jenetics.prog.ProgramChromosome;
import io.jenetics.prog.ProgramGene;
import io.jenetics.prog.op.MathOp;
import io.jenetics.prog.op.Op;
import io.jenetics.prog.op.Var;
import io.jenetics.util.Factory;
import io.jenetics.util.ISeq;
import ruleMiningSemanticExtension.domain.PredictionCandidate;

/**
 * Combines Genetic Programming (tree structure) with a Genetic Algorithm (numeric weights).
 * Genotype: ProgramChromosome (index 0) + DoubleChromosome weight pool (index 1).
 *
 * Raw types are required because Jenetics does not support heterogeneous typed Genotypes —
 * all chromosomes in a Genotype<G> must share the same gene type G.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class HybridAggregator implements EvolvableAggregator {

    private ProgramGene<Double> program;
    private double[] weightsPool;

    private static final int WEIGHT_POOL_SIZE = 20;
    private static final int TOTAL_INPUTS = FeatureExtractor.FEATURE_COUNT + WEIGHT_POOL_SIZE;

    // condition > 0 → true branch, else → false branch
    private static final Op<Double> IF_POSITIVE =
        Op.of("if", 3, args -> args[0] > 0.0 ? args[1] : args[2]);

    // Protected division: returns 1.0 when denominator ≈ 0 (avoids infinity → 0.0 fallback gaming fitness)
    private static final Op<Double> SAFE_DIV =
        Op.of("sdiv", 2, v -> Math.abs(v[1]) < 1e-9 ? 1.0 : v[0] / v[1]);

    private static final ISeq<Op<Double>> OPERATIONS = ISeq.of(
        MathOp.ADD, MathOp.SUB, MathOp.MUL, SAFE_DIV, IF_POSITIVE
    );

    private static final ISeq<Op<Double>> TERMINALS;
    static {
        Op<Double>[] terms = new Op[TOTAL_INPUTS];
        terms[0] = Var.of("max", 0);
        terms[1] = Var.of("noisyOr", 1);
        terms[2] = Var.of("ruleCount", 2);
        terms[3] = Var.of("mean", 3);
        terms[4] = Var.of("totalGroundings", 4);
        terms[5] = Var.of("subgraphSize", 5);
        terms[6] = Var.of("avgRuleLength", 6);
        terms[7] = Var.of("density", 7);
        terms[8] = Var.of("avgDegree", 8);
        terms[9] = Var.of("domainRangeMatch", 9);
        for (int i = 0; i < WEIGHT_POOL_SIZE; i++) {
            terms[FeatureExtractor.FEATURE_COUNT + i] = Var.of("w" + i, FeatureExtractor.FEATURE_COUNT + i);
        }
        TERMINALS = ISeq.of(terms);
    }

    @Override
    public void configure(Genotype genotype) {
        this.program = (ProgramGene<Double>) genotype.get(0).gene();
        this.weightsPool = ((DoubleChromosome) genotype.get(1)).toArray();
    }

    @Override
    public Factory<Genotype> getGenotypeFactory() {
        return (Factory) Genotype.of(
            (Chromosome) ProgramChromosome.of(5, OPERATIONS, TERMINALS),
            (Chromosome) DoubleChromosome.of(0.0, 1.0, WEIGHT_POOL_SIZE)
        );
    }

    @Override
    public double scoreFeatures(Double[] features) {
        if (program == null || weightsPool == null) return 0.0;
        // Pre-computed features cover slots 0..FEATURE_COUNT-1; weights fill the rest.
        Double[] inputs = new Double[TOTAL_INPUTS];
        System.arraycopy(features, 0, inputs, 0, FeatureExtractor.FEATURE_COUNT);
        for (int i = 0; i < WEIGHT_POOL_SIZE; i++) {
            inputs[FeatureExtractor.FEATURE_COUNT + i] = weightsPool[i];
        }
        try {
            double result = program.apply(inputs);
            return Double.isFinite(result) ? result : 0.0;
        } catch (Exception e) {
            return 0.0;
        }
    }

    @Override
    public double aggregate(PredictionCandidate candidate) {
        return scoreFeatures(FeatureExtractor.extract(candidate));
    }

    @Override
    public EvolvableAggregator newInstance() {
        return new HybridAggregator();
    }

    @Override
    public String toString() {
        if (program == null) return "empty";
        String formula = program.toParenthesesString();
        if (weightsPool != null) {
            // Iterate in reverse to avoid partial-name replacement (e.g. "w1" matching inside "w10")
            for (int i = WEIGHT_POOL_SIZE - 1; i >= 0; i--) {
                formula = formula.replace("w" + i, String.format("%.3f", weightsPool[i]));
            }
        }
        return formula;
    }
}

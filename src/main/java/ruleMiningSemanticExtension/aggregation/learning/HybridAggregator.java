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
    private int treeDepth = 5;

    private static final int WEIGHT_POOL_SIZE = 20;
    private static final int TOTAL_INPUTS = FeatureExtractor.FEATURE_COUNT + WEIGHT_POOL_SIZE;

    // Protected division: returns 1.0 when denominator ≈ 0 (avoids infinity → 0.0 fallback gaming fitness)
    private static final Op<Double> SAFE_DIV =
        Op.of("sdiv", 2, v -> Math.abs(v[1]) < 1e-9 ? 1.0 : v[0] / v[1]);

    // "max2"/"min2" to avoid name collision with the terminal "max" (feature index 0)
    private static final Op<Double> MAX_OP =
        Op.of("max2", 2, args -> Math.max(args[0], args[1]));

    private static final Op<Double> MIN_OP =
        Op.of("min2", 2, args -> Math.min(args[0], args[1]));

    private static final ISeq<Op<Double>> OPERATIONS = ISeq.of(
        MathOp.ADD, MathOp.SUB, MathOp.MUL, SAFE_DIV, MAX_OP, MIN_OP
    );

    private static final ISeq<Op<Double>> TERMINALS;
    static {
        Op<Double>[] terms = new Op[TOTAL_INPUTS];
        terms[0] = Var.of("max", 0);
        terms[1] = Var.of("noisyOr", 1);
        terms[2] = Var.of("ruleCount", 2);
        terms[3] = Var.of("totalGroundings", 3);
        terms[4] = Var.of("subgraphSize", 4);
        terms[5] = Var.of("avgRuleLength", 5);
        terms[6] = Var.of("avgDegree", 6);
        terms[7] = Var.of("domainRangeMatch", 7);
        terms[8] = Var.of("avgInDegree", 8);
        terms[9] = Var.of("avgOutDegree", 9);
        terms[10] = Var.of("avgMaxTypeDepth", 10);
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
    public void setTreeDepth(int depth) { this.treeDepth = depth; }

    @Override
    public Factory<Genotype> getGenotypeFactory() {
        return (Factory) Genotype.of(
            (Chromosome) ProgramChromosome.of(treeDepth, OPERATIONS, TERMINALS),
            (Chromosome) DoubleChromosome.of(0.0, 1.0, WEIGHT_POOL_SIZE)
        );
    }

    /**
     * Thread-local boxing buffer: Jenetics' ProgramGene.apply() requires Double[].
     * Features are primitive double[] everywhere else; we box once here per call.
     * The buffer covers both the feature slots and the weight-pool slots.
     */
    private static final ThreadLocal<Double[]> INPUTS_BUF =
        ThreadLocal.withInitial(() -> new Double[TOTAL_INPUTS]);

    @Override
    public double scoreFeatures(double[] features) {
        if (program == null || weightsPool == null) return 0.0;
        // Box into thread-local buffer — pre-computed features + evolved weights.
        Double[] inputs = INPUTS_BUF.get();
        for (int i = 0; i < FeatureExtractor.FEATURE_COUNT; i++) inputs[i] = features[i];
        for (int i = 0; i < WEIGHT_POOL_SIZE; i++) inputs[FeatureExtractor.FEATURE_COUNT + i] = weightsPool[i];
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
        HybridAggregator a = new HybridAggregator();
        a.treeDepth = this.treeDepth;
        return a;
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

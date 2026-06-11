package ruleMiningSemanticExtension.aggregation.learning;

import io.jenetics.Genotype;
import io.jenetics.prog.ProgramChromosome;
import io.jenetics.prog.ProgramGene;
import io.jenetics.prog.op.MathOp;
import io.jenetics.prog.op.Op;
import io.jenetics.prog.op.Var;
import io.jenetics.util.Factory;
import io.jenetics.util.ISeq;
import ruleMiningSemanticExtension.domain.PredictionCandidate;

public class SymbolicAggregator implements EvolvableAggregator<ProgramGene<Double>> {

    private ProgramGene<Double> program;
    private int treeDepth = 5;

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

    private static final ISeq<Op<Double>> TERMINALS = ISeq.of(
        Var.of("max", 0),
        Var.of("noisyOr", 1),
        Var.of("ruleCount", 2),
        Var.of("totalGroundings", 3),
        Var.of("subgraphSize", 4),
        Var.of("avgRuleLength", 5),
        Var.of("avgDegree", 6),
        Var.of("avgInDegree", 7),
        Var.of("avgOutDegree", 8),
        Var.of("domainRangeMatch", 9),
        Var.of("avgMaxTypeDepth", 10)
    );

    /**
     * Thread-local boxing buffer: Jenetics' ProgramGene.apply() requires Double[].
     * We keep features as primitive double[] everywhere else; box once here per call.
     */
    private static final ThreadLocal<Double[]> BOXED_BUF =
        ThreadLocal.withInitial(() -> new Double[FeatureExtractor.FEATURE_COUNT]);

    @Override
    public void configure(Genotype<ProgramGene<Double>> genotype) {
        this.program = genotype.gene();
    }

    @Override
    public void setTreeDepth(int depth) { this.treeDepth = depth; }

    @Override
    public Factory<Genotype<ProgramGene<Double>>> getGenotypeFactory() {
        return Genotype.of(ProgramChromosome.of(treeDepth, OPERATIONS, TERMINALS));
    }

    @Override
    public double scoreFeatures(double[] features) {
        if (program == null) return 0.0;
        try {
            Double[] boxed = BOXED_BUF.get();
            for (int i = 0; i < features.length; i++) boxed[i] = features[i];
            double result = program.apply(boxed);
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
    public EvolvableAggregator<ProgramGene<Double>> newInstance() {
        SymbolicAggregator a = new SymbolicAggregator();
        a.treeDepth = this.treeDepth;
        return a;
    }

    @Override
    public String toString() {
        return program == null ? "empty" : program.toParenthesesString();
    }
}

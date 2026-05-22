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

    // condition > 0 → true branch, else → false branch
    private static final Op<Double> IF_POSITIVE =
        Op.of("if", 3, args -> args[0] > 0.0 ? args[1] : args[2]);

    // Protected division: returns 1.0 when denominator ≈ 0 (avoids infinity → 0.0 fallback gaming fitness)
    private static final Op<Double> SAFE_DIV =
        Op.of("sdiv", 2, v -> Math.abs(v[1]) < 1e-9 ? 1.0 : v[0] / v[1]);

    private static final ISeq<Op<Double>> OPERATIONS = ISeq.of(
        MathOp.ADD, MathOp.SUB, MathOp.MUL, SAFE_DIV, IF_POSITIVE
    );

    private static final ISeq<Op<Double>> TERMINALS = ISeq.of(
        Var.of("max", 0),
        Var.of("noisyOr", 1),
        Var.of("ruleCount", 2),
        Var.of("mean", 3),
        Var.of("totalGroundings", 4),
        Var.of("subgraphSize", 5),
        Var.of("avgRuleLength", 6),
        Var.of("density", 7),
        Var.of("avgDegree", 8),
        Var.of("domainRangeMatch", 9)
    );

    @Override
    public void configure(Genotype<ProgramGene<Double>> genotype) {
        this.program = genotype.gene();
    }

    @Override
    public Factory<Genotype<ProgramGene<Double>>> getGenotypeFactory() {
        return Genotype.of(ProgramChromosome.of(5, OPERATIONS, TERMINALS));
    }

    @Override
    public double scoreFeatures(Double[] features) {
        if (program == null) return 0.0;
        try {
            double result = program.apply(features);
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
        return new SymbolicAggregator();
    }

    @Override
    public String toString() {
        return program == null ? "empty" : program.toParenthesesString();
    }
}

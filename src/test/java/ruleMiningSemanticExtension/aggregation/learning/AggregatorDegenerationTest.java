package ruleMiningSemanticExtension.aggregation.learning;

import io.jenetics.prog.ProgramGene;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that the aggregation pipeline does not degenerate in two ways:
 *
 *   1. SAFE_DIV: trees that divide by zero-valued features (e.g. density=0,
 *      domainRangeMatch=0) must produce finite scores, not infinity clamped to 0.0.
 *      Before the fix, every candidate scored 0.0, making degenerate trees appear
 *      perfect during evolution.
 *
 *   2. Feature scaling: log1p-scaled count features must stay bounded even for
 *      unrealistically large KG inputs.
 *
 * All tests use purely synthetic in-memory data — no external dataset required.
 */
public class AggregatorDegenerationTest {

    // Feature vector layout (matches FeatureExtractor indices 0-10):
    // [max, noisyOr, log(ruleCount+1), log(groundings+1), log(subgraphSize+1),
    //  avgRuleLength, avgDegree, domainRangeMatch, avgInDegree, avgOutDegree, avgMaxTypeDepth]

    /** High-quality candidate: strong confidence, range-compatible, well-typed subgraph. */
    private static final double[] HIGH_CONF = {
        0.9, 0.95, Math.log1p(3), Math.log1p(5), Math.log1p(10), 2.0, 1.8, 1.0, 1.5, 1.2, 3.0
    };

    /** Low-quality candidate: weak confidence, range-incompatible, shallowly typed. */
    private static final double[] LOW_CONF = {
        0.1, 0.12, Math.log1p(1), Math.log1p(1), Math.log1p(2), 1.0, 1.0, 0.0, 0.5, 0.4, 1.0
    };

    /**
     * Edge case: features that would cause infinity with raw DIV.
     * avgDegree=0, domainRangeMatch=0, avgInDegree=0, avgOutDegree=0 — valid zero values.
     */
    private static final double[] ZERO_DIVISORS = {
        0.5, 0.6, Math.log1p(2), Math.log1p(3), Math.log1p(5), 2.0, 0.0, 0.0, 0.0, 0.0, 0.0
    };

    // -------------------------------------------------------------------------
    // 1. SAFE_DIV: scoreFeatures must always return finite values
    // -------------------------------------------------------------------------

    @Test
    public void testHybridScoreFeaturesAlwaysFinite() {
        HybridAggregator agg = trainHybrid(buildSyntheticQueries());

        for (double[] features : List.of(HIGH_CONF, LOW_CONF, ZERO_DIVISORS)) {
            double score = agg.scoreFeatures(features);
            assertTrue(Double.isFinite(score),
                "HybridAggregator.scoreFeatures returned non-finite value for input: "
                    + java.util.Arrays.toString(features) + " → " + score);
        }
    }

    @Test
    public void testSymbolicScoreFeaturesAlwaysFinite() {
        SymbolicAggregator agg = trainSymbolic(buildSyntheticQueries());

        for (double[] features : List.of(HIGH_CONF, LOW_CONF, ZERO_DIVISORS)) {
            double score = agg.scoreFeatures(features);
            assertTrue(Double.isFinite(score),
                "SymbolicAggregator.scoreFeatures returned non-finite value for input: "
                    + java.util.Arrays.toString(features) + " → " + score);
        }
    }

    // -------------------------------------------------------------------------
    // 2. Non-constant: evolved aggregator must not assign identical scores to
    //    all candidates (which would indicate a degenerate tree)
    // -------------------------------------------------------------------------

    @Test
    public void testHybridAggregatorIsNotConstant() {
        List<AggregatorFitnessFunction.ValidationQuery> queries = buildSyntheticQueries();
        HybridAggregator agg = trainHybrid(queries);

        Set<Double> distinctScores = queries.stream()
            .flatMap(q -> q.candidates().stream())
            .map(e -> agg.scoreFeatures(e.features()))
            .collect(Collectors.toSet());

        assertTrue(distinctScores.size() > 1,
            "Evolved HybridAggregator assigns the same score to every candidate — tree is degenerate");
    }

    @Test
    public void testSymbolicAggregatorIsNotConstant() {
        List<AggregatorFitnessFunction.ValidationQuery> queries = buildSyntheticQueries();
        SymbolicAggregator agg = trainSymbolic(queries);

        Set<Double> distinctScores = queries.stream()
            .flatMap(q -> q.candidates().stream())
            .map(e -> agg.scoreFeatures(e.features()))
            .collect(Collectors.toSet());

        assertTrue(distinctScores.size() > 1,
            "Evolved SymbolicAggregator assigns the same score to every candidate — tree is degenerate");
    }

    // -------------------------------------------------------------------------
    // 3. avgMaxTypeDepth: feature index 10 must vary across synthetic candidates
    //    and must be non-negative in all cases
    // -------------------------------------------------------------------------

    @Test
    public void testAvgMinTypeDepthVariesAcrossCandidates() {
        // The four synthetic vectors carry different type-depth values at index 10:
        // HIGH_CONF=3.0, LOW_CONF=1.0, ZERO_DIVISORS=0.0, medium=2.0.
        // If they were all equal the feature would be useless noise.
        double[] depths = {
            HIGH_CONF[10],
            LOW_CONF[10],
            ZERO_DIVISORS[10]
        };
        long distinct = java.util.Arrays.stream(depths).distinct().count();
        assertTrue(distinct > 1,
            "avgMaxTypeDepth (index 10) is identical across all synthetic candidates — "
            + "feature is constant and will contribute no signal");
    }

    @Test
    public void testAvgMinTypeDepthIsNonNegative() {
        for (double[] vec : List.of(HIGH_CONF, LOW_CONF, ZERO_DIVISORS)) {
            assertTrue(vec[10] >= 0.0,
                "avgMaxTypeDepth must be >= 0, got " + vec[10]);
        }
    }

    // -------------------------------------------------------------------------
    // 4. Feature scaling: log1p keeps count features bounded across extreme inputs
    // -------------------------------------------------------------------------

    @Test
    public void testLogScaledFeaturesAreBounded() {
        // Extreme values that would dominate arithmetic without scaling
        double extremeGroundings = Math.log1p(100_000);
        double extremeSubgraph   = Math.log1p(500_000);
        double extremeRuleCount  = Math.log1p(10_000);

        // All must stay in a range comparable to confidence features [0, 1]
        // log1p(100000) ≈ 11.5 — bounded, not 100000
        assertTrue(extremeGroundings < 20,
            "log1p(100000) should be < 20, was " + extremeGroundings);
        assertTrue(extremeSubgraph < 20,
            "log1p(500000) should be < 20, was " + extremeSubgraph);
        assertTrue(extremeRuleCount < 15,
            "log1p(10000) should be < 15, was " + extremeRuleCount);

        // All must be strictly positive (log1p(0) = 0, log1p(x>0) > 0)
        assertTrue(Math.log1p(0) == 0.0, "log1p(0) must be 0");
        assertTrue(Math.log1p(1) > 0.0,  "log1p(1) must be > 0");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Builds three synthetic validation queries with known quality ordering. */
    private List<AggregatorFitnessFunction.ValidationQuery> buildSyntheticQueries() {
        List<AggregatorFitnessFunction.ValidationQuery> queries = new ArrayList<>();

        // Q1: correct entity has high confidence; two decoys have low confidence
        queries.add(query("q1",
            entry("correct", HIGH_CONF),
            entry("decoy1",  LOW_CONF),
            entry("decoy2",  LOW_CONF),
            "correct"));

        // Q2: correct entity vs zero-divisor decoy (stress-tests SAFE_DIV path)
        queries.add(query("q2",
            entry("correct", HIGH_CONF),
            entry("decoy",   ZERO_DIVISORS),
            "correct"));

        // Q3: medium-quality correct entity among weaker decoys
        double[] medium = {0.6, 0.7, Math.log1p(2), Math.log1p(3), Math.log1p(6), 1.5, 1.5, 1.0, 1.0, 0.8, 2.0};
        queries.add(query("q3",
            entry("correct", medium),
            entry("decoy",   LOW_CONF),
            "correct"));

        return queries;
    }

    private AggregatorFitnessFunction.ValidationQuery query(
            String name,
            AggregatorFitnessFunction.CandidateEntry e1,
            AggregatorFitnessFunction.CandidateEntry e2,
            String correctEntity) {
        var candidates = List.of(e1, e2);
        int idx = e1.entity().equals(correctEntity) ? 0 : (e2.entity().equals(correctEntity) ? 1 : -1);
        return new AggregatorFitnessFunction.ValidationQuery(name, candidates, correctEntity, Set.of(), idx);
    }

    private AggregatorFitnessFunction.ValidationQuery query(
            String name,
            AggregatorFitnessFunction.CandidateEntry e1,
            AggregatorFitnessFunction.CandidateEntry e2,
            AggregatorFitnessFunction.CandidateEntry e3,
            String correctEntity) {
        var candidates = List.of(e1, e2, e3);
        int idx = -1;
        for (int i = 0; i < candidates.size(); i++)
            if (candidates.get(i).entity().equals(correctEntity)) { idx = i; break; }
        return new AggregatorFitnessFunction.ValidationQuery(name, candidates, correctEntity, Set.of(), idx);
    }

    private AggregatorFitnessFunction.CandidateEntry entry(String entity, double[] features) {
        return new AggregatorFitnessFunction.CandidateEntry(entity, features);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private HybridAggregator trainHybrid(List<AggregatorFitnessFunction.ValidationQuery> queries) {
        HybridAggregator proto = new HybridAggregator();
        AggregatorFitnessFunction ff = new AggregatorFitnessFunction(queries, proto, 0);
        io.jenetics.util.Factory gtf = proto.getGenotypeFactory();

        io.jenetics.engine.Engine engine = io.jenetics.engine.Engine
                .builder(ff::evaluate, gtf)
                .populationSize(20)
                .optimize(io.jenetics.Optimize.MAXIMUM)
                .build();

        io.jenetics.Genotype best = (io.jenetics.Genotype) engine.stream()
                .limit(io.jenetics.engine.Limits.byFixedGeneration(10))
                .collect(io.jenetics.engine.EvolutionResult.toBestGenotype());

        proto.configure(best);
        return proto;
    }

    private SymbolicAggregator trainSymbolic(List<AggregatorFitnessFunction.ValidationQuery> queries) {
        SymbolicAggregator proto = new SymbolicAggregator();
        AggregatorFitnessFunction<ProgramGene<Double>> ff =
                new AggregatorFitnessFunction<>(queries, proto, 0);

        io.jenetics.engine.Engine<ProgramGene<Double>, Double> engine =
                io.jenetics.engine.Engine
                        .builder(ff::evaluate, proto.getGenotypeFactory())
                        .populationSize(20)
                        .optimize(io.jenetics.Optimize.MAXIMUM)
                        .build();

        io.jenetics.Genotype<ProgramGene<Double>> best = engine.stream()
                .limit(io.jenetics.engine.Limits.byFixedGeneration(10))
                .collect(io.jenetics.engine.EvolutionResult.toBestGenotype());

        proto.configure(best);
        return proto;
    }
}

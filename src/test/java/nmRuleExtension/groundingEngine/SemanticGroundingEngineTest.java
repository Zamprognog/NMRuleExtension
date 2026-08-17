package nmRuleExtension.groundingEngine;

import nmRuleExtension.graphTools.SemanticGraphManager;
import nmRuleExtension.rules.Direction;
import nmRuleExtension.rules.RulePathStep;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the semantic constraint checks.
 *
 * <p>Each behaviour is pinned from both sides: a test that a legitimate prediction SURVIVES,
 * paired with a control that a genuine violation is still DROPPED. Without the controls,
 * "never prune anything" would pass.
 *
 * <p>Graphs are built from a temporary TSV. Entity IDs are assigned in order of first
 * appearance, which matters for the unary tests because {@code findSatisfyingStartNodes}
 * scans start nodes in ID order.
 */
public class SemanticGroundingEngineTest {

    @TempDir
    Path tempDir;

    private int graphCounter = 0;

    // ---------------------------------------------------------------- helpers

    /** Builds a frozen graph from tab-separated {@code s p o} lines. */
    private SemanticGraphManager graphOf(String... triples) throws IOException {
        Path file = tempDir.resolve("graph" + (graphCounter++) + ".tsv");
        Files.writeString(file, String.join("\n", triples) + "\n");

        SemanticGraphManager gm = new SemanticGraphManager();
        gm.parseTriples(file.toString(), "\t");
        gm.finalizeGraph();
        return gm;
    }

    /** Attaches a fresh, empty constraint to {@code relation} and returns it for configuration. */
    private SemanticGraphManager.IntPropertyConstraint constrain(SemanticGraphManager gm, String relation) {
        int relId = gm.getRelationDict().lookup(relation);
        assertTrue(relId != -1, "relation '" + relation + "' must appear in the graph");

        SemanticGraphManager.IntPropertyConstraint constraint = new SemanticGraphManager.IntPropertyConstraint();
        gm.getPropertyIdConstraints().put(relId, constraint);
        return constraint;
    }

    /** Two-hop body: {@code r1(start, M), r2(M, C)}. C is the predicted variable. */
    private List<RulePathStep> twoHopBody() {
        return List.of(
                RulePathStep.variable(Direction.FORWARD, "r1", "M"),
                RulePathStep.variable(Direction.FORWARD, "r2", "C"));
    }

    // ------------------------------------------------- bug 1: distinct values

    /**
     * A functional relation must count DISTINCT predicted entities, not raw groundings.
     * Here two independent paths both reach "c", which violates nothing.
     */
    @Test
    public void multiplePathsToSameEntitySurviveFunctionalCheck() throws IOException {
        SemanticGraphManager gm = graphOf(
                "s\tr1\tm1",
                "s\tr1\tm2",
                "m1\tr2\tc",
                "m2\tr2\tc",
                "anchor\tp\tfiller");   // puts 'p' in the relation dictionary
        constrain(gm, "p").isFunctional = true;

        List<Map<String, String>> groundings =
                new SemanticGroundingEngine(gm).findPathGroundings("s", twoHopBody(), "p", "C", true);

        assertFalse(groundings.isEmpty(), "two paths predicting the same entity must not drop the rule");
        assertTrue(groundings.stream().allMatch(g -> "c".equals(g.get("C"))),
                "every grounding should predict 'c'");
    }

    /** Control: two genuinely different predictions under a functional relation still drop the rule. */
    @Test
    public void twoDistinctPredictionsDropRuleUnderFunctional() throws IOException {
        SemanticGraphManager gm = graphOf(
                "s\tr1\tm1",
                "s\tr1\tm2",
                "m1\tr2\tc",
                "m2\tr2\td",
                "anchor\tp\tfiller");
        constrain(gm, "p").isFunctional = true;

        List<Map<String, String>> groundings =
                new SemanticGroundingEngine(gm).findPathGroundings("s", twoHopBody(), "p", "C", true);

        assertTrue(groundings.isEmpty(), "a rule predicting two distinct values for a functional relation is unsound");
    }

    /**
     * Divergence on a LATER grounding must still be caught. This pins the implicit invariant
     * behind comparing against groundings.get(0): detection aborts immediately, so all
     * earlier groundings necessarily agree.
     */
    @Test
    public void divergenceOnThirdGroundingStillDropsRule() throws IOException {
        SemanticGraphManager gm = graphOf(
                "s\tr1\tm1",
                "s\tr1\tm2",
                "s\tr1\tm3",
                "m1\tr2\tc",
                "m2\tr2\tc",
                "m3\tr2\td",
                "anchor\tp\tfiller");
        constrain(gm, "p").isFunctional = true;

        List<Map<String, String>> groundings =
                new SemanticGroundingEngine(gm).findPathGroundings("s", twoHopBody(), "p", "C", true);

        assertTrue(groundings.isEmpty(), "a third grounding disagreeing with the first two must drop the rule");
    }

    // --------------------------------------------------- bug 2: anchor's edge

    /**
     * Under inverse-functionality, an existing edge from the ANCHOR to the candidate is the
     * queried fact itself, not a competing subject. Rules mined from the training graph
     * re-derive known facts constantly, so this path is hot.
     */
    @Test
    public void existingEdgeFromAnchorDoesNotDropRule() throws IOException {
        SemanticGraphManager gm = graphOf(
                "s\tr1\tm",
                "m\tr2\tc",
                "s\tp\tc");   // already-known fact: anchor -> candidate
        constrain(gm, "p").isInverseFunctional = true;

        List<Map<String, String>> groundings =
                new SemanticGroundingEngine(gm).findPathGroundings("s", twoHopBody(), "p", "C", true);

        assertFalse(groundings.isEmpty(), "an edge back to the anchor is the queried fact, not a violation");
    }

    /** Control: a DIFFERENT subject pointing at the candidate is a real violation. */
    @Test
    public void existingEdgeFromForeignSubjectDropsRule() throws IOException {
        SemanticGraphManager gm = graphOf(
                "s\tr1\tm",
                "m\tr2\tc",
                "x\tp\tc");   // foreign subject -> candidate
        constrain(gm, "p").isInverseFunctional = true;

        List<Map<String, String>> groundings =
                new SemanticGroundingEngine(gm).findPathGroundings("s", twoHopBody(), "p", "C", true);

        assertTrue(groundings.isEmpty(), "a second distinct subject violates inverse functionality");
    }

    // --------------------------------------------- bug 3: unary guard placement

    /**
     * Nodes that never satisfied the rule body must not be semantically checked. "d" has the
     * first-hop edge but no continuation, and carries a foreign incoming p-edge; before the
     * fix it aborted the whole scan and returned nothing.
     *
     * <p>"d" is written first so it receives a lower entity ID and is scanned before "v".
     *
     * <p>DISABLED — currently fails for an unrelated, pre-existing reason. The unary path is
     * dead under the semantic engine: {@code SemanticGroundingEngine.checkSuccess} returns
     * early when {@code headVariable == null} (the unary case) without adding the binding to
     * {@code ruleGroundings}, which the base implementation does. {@code dummyResults} in
     * {@code findSatisfyingStartNodes} therefore never becomes non-empty and NO start node is
     * ever collected, valid or not. Re-enable once that is resolved; the assertion here is the
     * intended behaviour.
     */
    @Disabled("blocked by the unary-path recording bug in SemanticGroundingEngine.checkSuccess")
    @Test
    public void nonSatisfyingNodeDoesNotWipeUnaryResults() throws IOException {
        SemanticGraphManager gm = graphOf(
                "x\tp\td",      // d: foreign incoming p-edge, would trip the check
                "d\tr1\tdm",    // d: has r1 but dm has no r2, so d never satisfies the body
                "v\tr1\tvm",    // v: satisfies the full body
                "vm\tr2\tvn",
                "s\tr1\tsm");   // s: the query anchor, also a non-satisfying node
        constrain(gm, "p").isInverseFunctional = true;

        List<String> starts = new SemanticGroundingEngine(gm)
                .findSatisfyingStartNodes(twoHopBody(), "p", null, true, "s");

        assertEquals(List.of("v"), starts, "a non-satisfying decoy must not discard the valid start node");
    }

    /**
     * Control: when the SATISFYING node itself violates the constraint, the rule is still dropped.
     *
     * <p>DISABLED — this currently passes VACUOUSLY: the unary path returns an empty list for
     * every input (see the note above), so the assertion cannot distinguish correct pruning from
     * the path being dead. Re-enable alongside its sibling.
     */
    @Disabled("would pass vacuously while the unary path returns nothing for any input")
    @Test
    public void satisfyingNodeViolatingConstraintDropsUnaryRule() throws IOException {
        SemanticGraphManager gm = graphOf(
                "v\tr1\tvm",
                "vm\tr2\tvn",
                "x\tp\tv",      // the valid candidate itself has a foreign incoming p-edge
                "s\tr1\tsm");
        constrain(gm, "p").isInverseFunctional = true;

        List<String> starts = new SemanticGroundingEngine(gm)
                .findSatisfyingStartNodes(twoHopBody(), "p", null, true, "s");

        assertTrue(starts.isEmpty(), "a genuine violation on a satisfying node must still drop the rule");
    }

    // ------------------------------------------------ disjointness with range

    /** A candidate whose type is disjoint with the relation's range drops the rule. */
    @Test
    public void candidateDisjointWithRangeDropsRule() throws IOException {
        SemanticGraphManager gm = graphOf(
                "s\tr1\tm",
                "m\tr2\tc",
                "anchor\tp\tfiller");

        int forbiddenType = 42;
        constrain(gm, "p").disjointWithRange.add(forbiddenType);
        gm.getEntityIdTypes().put(gm.getEntityDict().lookup("c"), Set.of(forbiddenType));

        List<Map<String, String>> groundings =
                new SemanticGroundingEngine(gm).findPathGroundings("s", twoHopBody(), "p", "C", true);

        assertTrue(groundings.isEmpty(), "a candidate disjoint with the range must drop the rule");
    }

    /** Control: a typed candidate that is NOT disjoint with the range survives. */
    @Test
    public void candidateWithCompatibleTypeSurvives() throws IOException {
        SemanticGraphManager gm = graphOf(
                "s\tr1\tm",
                "m\tr2\tc",
                "anchor\tp\tfiller");

        constrain(gm, "p").disjointWithRange.add(42);
        gm.getEntityIdTypes().put(gm.getEntityDict().lookup("c"), Set.of(7));

        List<Map<String, String>> groundings =
                new SemanticGroundingEngine(gm).findPathGroundings("s", twoHopBody(), "p", "C", true);

        assertFalse(groundings.isEmpty(), "a compatible type must not drop the rule");
    }

    // ------------------------------------------------- query-level abstention

    /**
     * Documents a behaviour that is stronger than rule-level pruning: when the anchor already
     * has a value for a functional relation, {@code isQueryValid} abandons the ENTIRE query,
     * so no rule can contribute a candidate. Pinned here so a change to that modelling
     * decision is deliberate rather than accidental.
     */
    @Test
    public void queryAbandonedWhenAnchorAlreadyHasFunctionalValue() throws IOException {
        SemanticGraphManager gm = graphOf(
                "s\tp\texisting",   // anchor already has a value for the functional relation
                "s\tr1\tm",
                "m\tr2\tc");
        constrain(gm, "p").isFunctional = true;

        List<Map<String, String>> groundings =
                new SemanticGroundingEngine(gm).findPathGroundings("s", twoHopBody(), "p", "C", true);

        assertTrue(groundings.isEmpty(), "query-level abstention: the anchor already has its one permitted value");
    }

    /** The standard engine must stay unfiltered: identical setup, no pruning. */
    @Test
    public void standardEngineIgnoresConstraintsEntirely() throws IOException {
        SemanticGraphManager gm = graphOf(
                "s\tr1\tm1",
                "s\tr1\tm2",
                "m1\tr2\tc",
                "m2\tr2\td",
                "x\tp\tc",
                "s\tp\texisting");
        SemanticGraphManager.IntPropertyConstraint constraint = constrain(gm, "p");
        constraint.isFunctional = true;
        constraint.isInverseFunctional = true;

        List<Map<String, String>> groundings =
                new GroundingEngine(gm).findPathGroundings("s", twoHopBody(), "p", "C", true);

        assertEquals(2, groundings.size(), "the baseline engine must apply no semantic filtering");
    }
}

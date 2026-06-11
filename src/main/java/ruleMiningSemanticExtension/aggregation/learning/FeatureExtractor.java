package ruleMiningSemanticExtension.aggregation.learning;

import ruleMiningSemanticExtension.domain.PredictionCandidate;
import ruleMiningSemanticExtension.graphTools.Graph;
import ruleMiningSemanticExtension.graphTools.SemanticGraphManager;
import ruleMiningSemanticExtension.rules.Rule;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Extracts a fixed-length feature vector from a PredictionCandidate.
 *
 * Feature layout (FEATURE_COUNT = 11):
 *   0  max               — max rule confidence
 *   1  noisyOr           — noisy-OR combination of confidences
 *   2  ruleCount         — log1p(number of supporting rules)
 *   3  totalGroundings   — log1p(total grounding paths)
 *   4  subgraphSize      — log1p(total subgraph edges)
 *   5  avgRuleLength     — average rule body length
 *   6  avgDegree         — log1p(2 * rawEdgeCount / subgraphNodeCount)
 *   7  avgInDegree       — log1p(mean in-degree of subgraph nodes in the full KG)
 *   8  avgOutDegree      — log1p(mean out-degree of subgraph nodes in the full KG)
 *   9  domainRangeMatch  — 1.0 if candidate matches predicate domain/range, else 0.0
 *  10  avgMaxTypeDepth   — mean over subgraph nodes of max hop-distance to owl:Thing
 *                          (most-specific type depth; discriminative even when transitive
 *                          superclasses are stored, unlike min which always returns 1)
 *
 * Min-max normalization is applied to all features once fitNormalization() is called.
 */
public class FeatureExtractor {

    public static final int FEATURE_COUNT = 11;

    public static final String[] FEATURE_NAMES = {
        "max", "noisyOr", "ruleCount",
        "totalGroundings", "subgraphSize", "avgRuleLength",
        "avgDegree", "avgInDegree", "avgOutDegree",
        "domainRangeMatch", "avgMaxTypeDepth"
    };

    private static SemanticGraphManager semanticGraphManager;

    // Normalization state — null until fitNormalization() is called.
    private static double[] featureMins   = null;
    private static double[] featureRanges = null;

    // Feature masking — null means all features enabled.
    private static String    featureGroup = "all";
    private static boolean[] featureMask  = null;

    public static void setSemanticGraphManager(SemanticGraphManager sgm) {
        semanticGraphManager = sgm;
    }

    /**
     * Restricts which features are active. Disabled features are zeroed in extract()
     * before normalization, so they contribute nothing to any aggregator.
     *
     * Groups:
     *   "rule_related"   — 0 max, 1 noisyOr, 2 ruleCount, 3 totalGroundings, 5 avgRuleLength
     *   "rule_structural"— above + 4 subgraphSize, 6 avgDegree, 7 avgInDegree, 8 avgOutDegree
     *   "all" (default)  — all 11 features enabled
     */
    public static void setFeatureGroup(String group) {
        featureGroup = (group == null || group.isBlank()) ? "all" : group.toLowerCase().trim();
        switch (featureGroup) {
            case "rule_related" -> {
                featureMask = new boolean[FEATURE_COUNT];
                featureMask[0] = featureMask[1] = featureMask[2] = featureMask[3] = featureMask[5] = true;
            }
            case "rule_structural" -> {
                featureMask = new boolean[FEATURE_COUNT];
                for (int i = 0; i <= 8; i++) featureMask[i] = true;
            }
            default -> {
                featureGroup = "all";
                featureMask  = null;
            }
        }
    }

    public static String getFeatureGroup() { return featureGroup; }

    // -------------------------------------------------------------------------
    // Normalization
    // -------------------------------------------------------------------------

    /**
     * Fits per-feature min-max statistics from all pre-extracted feature arrays in the
     * validation queries, then normalizes those arrays in-place.
     * After this call, every future extract() result is also normalized.
     */
    public static void fitNormalization(List<AggregatorFitnessFunction.ValidationQuery> queries) {
        double[] mins = new double[FEATURE_COUNT];
        double[] maxs = new double[FEATURE_COUNT];
        Arrays.fill(mins,  Double.MAX_VALUE);
        Arrays.fill(maxs, -Double.MAX_VALUE);

        for (var query : queries) {
            for (var entry : query.candidates()) {
                double[] f = entry.features();
                for (int i = 0; i < FEATURE_COUNT; i++) {
                    if (f[i] < mins[i]) mins[i] = f[i];
                    if (f[i] > maxs[i]) maxs[i] = f[i];
                }
            }
        }

        double[] ranges = new double[FEATURE_COUNT];
        for (int i = 0; i < FEATURE_COUNT; i++) {
            ranges[i] = maxs[i] - mins[i];
        }

        featureMins   = mins;
        featureRanges = ranges;

        // Normalize already-stored feature arrays in-place.
        for (var query : queries) {
            for (var entry : query.candidates()) {
                normalizeInPlace(entry.features()); // double[], primitive — no unboxing
            }
        }

        System.out.println("FeatureExtractor: normalization fitted from " + queries.size() + " validation queries.");
        for (int i = 0; i < FEATURE_COUNT; i++) {
            System.out.printf("  [%2d] %-18s  min=%8.4f  max=%8.4f%n",
                    i, FEATURE_NAMES[i], mins[i], mins[i] + ranges[i]);
        }
    }

    private static void normalizeInPlace(double[] features) {
        if (featureMins == null) return;
        for (int i = 0; i < FEATURE_COUNT; i++) {
            double r = featureRanges[i];
            features[i] = r < 1e-9 ? 0.0 : (features[i] - featureMins[i]) / r;
        }
    }

    // -------------------------------------------------------------------------
    // Feature extraction
    // -------------------------------------------------------------------------

    public static double[] extract(PredictionCandidate candidate) {
        float[] confidences = candidate.getConfidences();
        if (confidences.length == 0) {
            return new double[FEATURE_COUNT]; // all zeros
        }

        // --- Confidence-based features ---
        double max = 0.0, sumConf = 0.0, productComplement = 1.0;
        int ruleCount = confidences.length;
        for (float conf : confidences) {
            double d = conf;
            if (d > max) max = d;
            sumConf += d;
            productComplement *= (1.0 - d);
        }
        double noisyOr = 1.0 - productComplement;

        // --- Grounding / subgraph features ---
        double totalGroundings = Math.log1p(candidate.getGroundingCount());
        double subgraphSize    = Math.log1p(candidate.getSubgraphEdgeCount());
        double vCount          = candidate.getSubgraphNodeCount();
        double avgDegree       = vCount > 0 ? Math.log1p(2.0 * candidate.getSubgraphEdgeCount() / vCount) : 0.0;

        // --- Rule structure ---
        double totalRuleLength = 0;
        for (Rule r : candidate.getRules()) totalRuleLength += r.getBody().size();
        double avgRuleLength = totalRuleLength / ruleCount;

        // --- Ontology feature ---
        double domainRangeMatch = 0.0;
        if (semanticGraphManager != null) {
            boolean matches = candidate.isPredictObject()
                    ? semanticGraphManager.matchesRange(candidate.getEntity(), candidate.getTargetRelation())
                    : semanticGraphManager.matchesDomain(candidate.getEntity(), candidate.getTargetRelation());
            domainRangeMatch = matches ? 1.0 : 0.0;
        }

        // --- In/out degree of subgraph nodes ---
        double avgInDegree = 0.0, avgOutDegree = 0.0;
        Set<Integer> nodeIds = candidate.getSubgraphNodeIds();
        if (!nodeIds.isEmpty() && semanticGraphManager != null) {
            Graph g = semanticGraphManager.getGraph();
            double sumIn = 0.0, sumOut = 0.0;
            for (int nodeId : nodeIds) {
                sumIn  += g.getInDegree(nodeId);
                sumOut += g.getOutDegree(nodeId);
            }
            avgInDegree  = Math.log1p(sumIn  / nodeIds.size());
            avgOutDegree = Math.log1p(sumOut / nodeIds.size());
        }

        // --- Type depth feature ---
        double avgMaxTypeDepth = 0.0;
        if (!nodeIds.isEmpty() && semanticGraphManager != null) {
            double sumDepth = 0.0;
            for (int nodeId : nodeIds) {
                sumDepth += semanticGraphManager.getMaxTypeDepthForEntity(nodeId);
            }
            avgMaxTypeDepth = sumDepth / nodeIds.size();
        }

        double[] result = {
            max, noisyOr, Math.log1p(ruleCount),
            totalGroundings, subgraphSize, avgRuleLength,
            avgDegree, avgInDegree, avgOutDegree,
            domainRangeMatch, avgMaxTypeDepth
        };

        if (featureMask != null) {
            for (int i = 0; i < FEATURE_COUNT; i++) {
                if (!featureMask[i]) result[i] = 0.0;
            }
        }

        normalizeInPlace(result);
        return result;
    }
}

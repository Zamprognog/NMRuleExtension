package ruleMiningSemanticExtension.aggregation.learning;

import ruleMiningSemanticExtension.domain.PredictionCandidate;
import ruleMiningSemanticExtension.graphTools.SemanticGraphManager;
import ruleMiningSemanticExtension.rules.Rule;

import java.util.Arrays;
import java.util.List;

public class FeatureExtractor {

    public static final int FEATURE_COUNT = 10;

    private static SemanticGraphManager semanticGraphManager;

    public static void setSemanticGraphManager(SemanticGraphManager sgm) {
        semanticGraphManager = sgm;
    }

    public static Double[] extract(PredictionCandidate candidate) {
        List<Float> confidences = candidate.getConfidences();
        if (confidences.isEmpty()) {
            Double[] empty = new Double[FEATURE_COUNT];
            Arrays.fill(empty, 0.0);
            return empty;
        }

        double max = 0.0;
        double sumConf = 0.0;
        double productComplement = 1.0;
        int ruleCount = confidences.size();

        for (float conf : confidences) {
            double d = conf;
            if (d > max) max = d;
            sumConf += d;
            productComplement *= (1.0 - d);
        }

        double noisyOr = 1.0 - productComplement;
        double mean = sumConf / ruleCount;
        double totalGroundings = Math.log1p(candidate.getGroundingCount());
        double subgraphSize = Math.log1p(candidate.getSubgraphEdgeCount());

        double totalRuleLength = 0;
        for (Rule r : candidate.getRules()) {
            totalRuleLength += r.getBody().size();
        }
        double avgRuleLength = totalRuleLength / ruleCount;

        double vCount = candidate.getSubgraphNodeCount();
        double density = vCount > 1 ? subgraphSize / (vCount * (vCount - 1)) : 0.0;
        double avgDegree = vCount > 0 ? 2.0 * subgraphSize / vCount : 0.0;

        double domainRangeMatch = 0.0;
        if (semanticGraphManager != null) {
            boolean matches = candidate.isPredictObject()
                    ? semanticGraphManager.matchesRange(candidate.getEntity(), candidate.getTargetRelation())
                    : semanticGraphManager.matchesDomain(candidate.getEntity(), candidate.getTargetRelation());
            domainRangeMatch = matches ? 1.0 : 0.0;
        }

        return new Double[]{
            max, noisyOr, Math.log1p(ruleCount), mean,
            totalGroundings, subgraphSize, avgRuleLength, density, avgDegree, domainRangeMatch
        };
    }
}

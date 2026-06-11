package ruleMiningSemanticExtension.domain;

import ruleMiningSemanticExtension.rules.Rule;
import java.util.*;

public class PredictionCandidate {
    private final String entity;
    private final String targetRelation;
    private int subgraphEdgeCount = 0;
    private final Set<Integer> subgraphNodeIds = new HashSet<>();
    private int groundingCount;
    private final Set<Rule> rules;

    /** Lazily-computed cache; null whenever a new rule has been added since last access. */
    private float[] cachedConfidences;

    public PredictionCandidate(String entity, String targetRelation) {
        this.entity = entity;
        this.targetRelation = targetRelation;
        this.groundingCount = 0;
        this.rules = new HashSet<>();
    }

    public void addGrounding(Rule rule, List<Triple> pathTriples, int neighborhoodEdgeCount, Set<Integer> neighborhoodNodeIds) {
        this.groundingCount++;
        boolean isNew = this.rules.add(rule);
        if (isNew) cachedConfidences = null; // invalidate on genuinely new rule
        this.subgraphEdgeCount += pathTriples.size() + neighborhoodEdgeCount;
        this.subgraphNodeIds.addAll(neighborhoodNodeIds);
    }

    public String getEntity() {
        return entity;
    }

    public String getTargetRelation() {
        return targetRelation;
    }

    public Set<Rule> getRules() {
        return rules;
    }

    public int getSubgraphEdgeCount() {
        return subgraphEdgeCount;
    }

    public int getSubgraphNodeCount() {
        return subgraphNodeIds.size();
    }

    public Set<Integer> getSubgraphNodeIds() {
        return subgraphNodeIds;
    }

    public int getGroundingCount() {
        return groundingCount;
    }

    /**
     * Returns rule confidences as a primitive float array.
     * Result is cached after first call; the cache is invalidated whenever a new
     * (distinct) rule is added via {@link #addGrounding}.
     */
    public float[] getConfidences() {
        if (cachedConfidences == null) {
            float[] arr = new float[rules.size()];
            int i = 0;
            for (Rule r : rules) arr[i++] = r.getConfidence();
            cachedConfidences = arr;
        }
        return cachedConfidences;
    }

    private boolean predictObject = true;

    public void setPredictObject(boolean predictObject) {
        this.predictObject = predictObject;
    }

    public boolean isPredictObject() {
        return predictObject;
    }
}

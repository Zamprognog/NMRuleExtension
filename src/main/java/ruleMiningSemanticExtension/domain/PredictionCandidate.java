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

    public PredictionCandidate(String entity, String targetRelation) {
        this.entity = entity;
        this.targetRelation = targetRelation;
        this.groundingCount = 0;
        this.rules = new HashSet<>();
    }

    public void addGrounding(Rule rule, List<Triple> pathTriples, int neighborhoodEdgeCount, Set<Integer> neighborhoodNodeIds) {
        this.groundingCount++;
        this.rules.add(rule);
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

    public int getGroundingCount() {
        return groundingCount;
    }

    public List<Float> getConfidences() {
        List<Float> confidences = new ArrayList<>();
        for (Rule r : rules) {
            confidences.add(r.getConfidence());
        }
        return confidences;
    }

    private boolean predictObject = true;

    public void setPredictObject(boolean predictObject) {
        this.predictObject = predictObject;
    }

    public boolean isPredictObject() {
        return predictObject;
    }
}

package evolveAggregation.groundingEngine;

import java.util.*;

public class SemanticGroundingEngine extends GroundingEngine{

    private final Map<Integer, Set<Integer>> entityIdTypes;
    private final Map<Integer, SemanticGraphManager.IntPropertyConstraint> propertyIdConstraints;


    public SemanticGroundingEngine(SemanticGraphManager gm) {
        super(gm);
        this.entityIdTypes = gm.getEntityIdTypes();
        this.propertyIdConstraints = gm.getPropertyIdConstraints();
    }

    /**
     * Intercepts the query right at the start to ensure domain, range, and
     * functional properties are respected for the specific query node.
     */
    @Override
    protected boolean isQueryValid(String queryNodeStr, String targetRelationStr, Boolean predictObject) {
        int queryNodeId = entityDict.lookup(queryNodeStr);
        int targetRelationId = relationDict.lookup(targetRelationStr);

        if (targetRelationId == -1 || queryNodeId == -1) {
            return true; // Probably something is probably wrong in the semantic parsing phase
        }

        SemanticGraphManager.IntPropertyConstraint constraints = propertyIdConstraints.get(targetRelationId);
        if (constraints == null) {
            return true; // No constraints defined for this relation
        }

        Set<Integer> queryNodeTypes = entityIdTypes.getOrDefault(queryNodeId, Collections.emptySet());

        if (predictObject) {
            // Predicting (s, p, ?) -> queryNodeStr is the subject 's' (e.g., amy)

            // 1. Check DOMAIN of p
            if (constraints.domainClasses != null && !constraints.domainClasses.isEmpty()) {
                //TODO: at the moment it checks if domain matches, maybe check if any is disjoint
                if (Collections.disjoint(queryNodeTypes, constraints.domainClasses)) {
                    return false; // Subject lacks the required domain types
                }
            }

            // 3. Check FUNCTIONAL property of p
            if (constraints.isFunctional) {
                int[] existingTargets = graph.getForwardTargets(queryNodeId, targetRelationId);
                // If it already has an outgoing edge for this functional relation, fail
                return existingTargets == null || existingTargets.length <= 0;
            }
        } else {
            // Predicting (?, p, o) -> queryNodeStr is the object 'o' (e.g., london)

            // 2. Check RANGE of p
            if (constraints.rangeClasses != null && !constraints.rangeClasses.isEmpty()) {
                return !Collections.disjoint(queryNodeTypes, constraints.rangeClasses); // Object lacks the required range types
            }
        }

        return true; // All semantic checks passed, proceed with graph search
    }

    @Override
    protected boolean checkSuccess(int startNodeId, int[] bindings, String[] varNames, List<Map<String, String>> ruleGroundings, int targetRelationId, Boolean predictObject) {


        //todo: functionaliy - check in-graph too
        // 1. Process the standard success binding
        Map<String, String> successfulBinding = new HashMap<>();
        for (int i = 0; i < bindings.length; i++) {
            if (varNames[i] != null && bindings[i] != -1) {
                successfulBinding.put(varNames[i], entityDict.getString(bindings[i]));
            }
        }
        ruleGroundings.add(successfulBinding); //todo: check if set or list

        // 2. CHECK SEMANTIC CONSTRAINTS
        SemanticGraphManager.IntPropertyConstraint targetRelationConstraints = propertyIdConstraints.get(targetRelationId);
        // functional case
        if (targetRelationConstraints.isFunctional && ruleGroundings.size() > 1) {
            // Clear the results because this rule is fundamentally invalid
            ruleGroundings.clear();
            return false;
        }

        // domain and range


        // Constraint passed, continue searching for more bindings
        return true;
    }

}

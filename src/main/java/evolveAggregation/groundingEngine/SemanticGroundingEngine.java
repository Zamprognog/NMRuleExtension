package evolveAggregation.groundingEngine;

import evolveAggregation.graphTools.Graph;
import evolveAggregation.graphTools.SemanticGraphManager;

import java.util.*;
import java.util.stream.IntStream;

public class SemanticGroundingEngine extends GroundingEngine{

    private final Map<Integer, Set<Integer>> entityIdTypes;
    private final Map<Integer, SemanticGraphManager.IntPropertyConstraint> propertyIdConstraints;
    private final Map<Integer, Set<Integer>> disjointClasses;

    public SemanticGroundingEngine(SemanticGraphManager gm) {
        super(gm);
        this.entityIdTypes = gm.getEntityIdTypes();
        this.propertyIdConstraints = gm.getPropertyIdConstraints();
        this.disjointClasses = gm.getDisjointClasses();
    }

    /**
     * Intercepts the query right at the start to ensure domain, range, and
     * functional properties are respected for the specific query node.
     */
    @Override
    protected boolean isQueryValid(String queryNodeStr, String targetRelationStr, Boolean predictObject) {
        int queryNodeId = entityDict.lookup(queryNodeStr);
        int headRelationId = relationDict.lookup(targetRelationStr);

        if (headRelationId == -1 || queryNodeId == -1) {
            return true; // Probably something is probably wrong in the semantic parsing phase
        }

        SemanticGraphManager.IntPropertyConstraint propertyConstraint = propertyIdConstraints.get(headRelationId);
        if (propertyConstraint == null) {
            return true; // No constraints defined for this relation
        }
        SemanticGraphManager.IntPropertyConstraint targetRelationConstraints = propertyIdConstraints.get(headRelationId);

        Set<Integer> queryNodeTypes = entityIdTypes.getOrDefault(queryNodeId, Collections.emptySet());

        if (predictObject) {
            // Predicting (s, p, ?) -> queryNodeStr is the subject 's' (e.g., amy)

            // 1. Check DOMAIN of p

            if (hasDisjointConflict(queryNodeTypes, targetRelationConstraints.disjointWithDomain)) return false;

            // 3. Check FUNCTIONAL property of p
            if (propertyConstraint.isFunctional) {
                int[] existingTargets = graph.getForwardTargets(queryNodeId, headRelationId);
                // If it already has an outgoing edge for this functional relation, fail
                return existingTargets == null || existingTargets.length <= 0;
            }
        } else {
            // Predicting (?, p, o) -> queryNodeStr is the object 'o' (e.g., london)

            // 2. Check RANGE of p
            if (hasDisjointConflict(queryNodeTypes, targetRelationConstraints.disjointWithRange)) return false;

            // 3. Check FUNCTIONAL property of p
            if (propertyConstraint.isFunctional) {
                // If we are predicting subject (?, p, o) and p is functional,
                // we'll check each predicted subject in checkSuccess/checkStartingNodeSuccess.
                // However, if the object 'o' itself already has multiple subjects, it's not a functional issue, but inverse functional.
                // Wait, if p is functional, then ANY s can have at most one o.
                // In isQueryValid, queryNodeId is 'o'. We don't know 's' yet.
                // So we can't check if 's' already has an outgoing 'p' edge here.
            }

            // 4. Check INVERSE FUNCTIONAL property of p
            if (propertyConstraint.isInverseFunctional) {
                int[] existingSubjects = graph.getBackwardTargets(queryNodeId, headRelationId);
                // If it already has an incoming edge for this inverse functional relation, fail
                return existingSubjects == null || existingSubjects.length <= 0;
            }
        }

        return true; // All semantic checks passed, proceed with graph search
    }

    @Override
    protected boolean checkSuccess(Graph graph, int startNodeId, String headVariable, int[] bindings, String[] varNames, List<Map<String, String>> ruleGroundings, int targetRelationId, Boolean predictObject) {

        if (headVariable ==null) return true; //happens only in unary rules when doing findSatisfyingStart nodes, deals with it in the method

        int predictedEntityId =  this.entityDict.lookup(headVariable); //clunky but unary rules don't have a head variable usually
        if (predictedEntityId == -1) {
            int headVariableIndex = IntStream.range(0, varNames.length)
                    .filter(i -> varNames[i].equals(headVariable))
                    .findFirst()
                    .orElse(-1);

            if (headVariableIndex == -1) return true;
            predictedEntityId = bindings[headVariableIndex];
        }

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

        if (targetRelationConstraints != null) {
            // functional and inverse functional case - in rule issue (in-graph dealt by @isQueryValid)
            if (predictObject) {
                if (targetRelationConstraints.isFunctional && ruleGroundings.size() > 1) {
                    // Clear the results because this rule is fundamentally invalid
                    ruleGroundings.clear();
                    return false;
                }
            } else {
                if (targetRelationConstraints.isFunctional) {
                    int[] existingTargets = graph.getForwardTargets(predictedEntityId, targetRelationId);
                    if (existingTargets != null && existingTargets.length > 0) {
                        ruleGroundings.clear();
                        return false;
                    }
                }
                if (targetRelationConstraints.isInverseFunctional && ruleGroundings.size() > 1) {
                    // Clear the results because this rule is fundamentally invalid
                    ruleGroundings.clear();
                    return false;
                }
            }

            // domain and range
            Set<Integer> predictedNodeTypes = entityIdTypes.getOrDefault(predictedEntityId, Collections.emptySet());

            if (!predictedNodeTypes.isEmpty()) {
                if (predictObject) {
                    // Predicting Object -> check against Range
                    if (hasDisjointConflict(predictedNodeTypes, targetRelationConstraints.disjointWithRange)) {
                        ruleGroundings.clear();
                        return false;
                    }
                } else {
                    // Predicting Subject -> check against Domain
                    if (hasDisjointConflict(predictedNodeTypes, targetRelationConstraints.disjointWithDomain)) {
                        ruleGroundings.clear();
                        return false;
                    }
                }
            }
        }

        // Constraint passed, continue searching for more bindings
        return true;
    }

    @Override
    protected boolean checkStartingNodeSuccess(Graph graph, List<String> validStartNodes, int knownEntityId, int headRelationId, int startNodeId, Boolean predictObject) {
        SemanticGraphManager.IntPropertyConstraint targetRelationConstraints = propertyIdConstraints.get(headRelationId);

        if (targetRelationConstraints != null) {
            // functional and inverse functional case - in rule issue (in-graph dealt by @isQueryValid)
            if (predictObject) {
                if (targetRelationConstraints.isFunctional && validStartNodes.size() > 1) return false;
            } else {
                if (targetRelationConstraints.isFunctional) {
                    int[] existingTargets = graph.getForwardTargets(startNodeId, headRelationId);
                    if (existingTargets != null && existingTargets.length > 0) return false;
                }
                if (targetRelationConstraints.isInverseFunctional && validStartNodes.size() > 1) return false;
            }

            // domain and range
            Set<Integer> predictedNodeTypes = entityIdTypes.getOrDefault(startNodeId, Collections.emptySet());

            if (!predictedNodeTypes.isEmpty()) {
                // Use the new disjoint conflict checker
                if (predictObject) {
                    // Predicting Object -> check against Range
                    if (hasDisjointConflict(predictedNodeTypes, targetRelationConstraints.disjointWithRange)) return false;
                } else {
                    // Predicting Subject -> check against Domain
                    if (hasDisjointConflict(predictedNodeTypes, targetRelationConstraints.disjointWithDomain)) return false;
                }
            }
        }
        return true;
    }

    /**
     * Helper method implementing Open World disjointness logic.
     * Returns TRUE if there is a conflict (meaning it should be rejected).
     */
    private boolean hasDisjointConflict(Set<Integer> entityTypes, Set<Integer> precomputedDisjointClasses) {
        if (precomputedDisjointClasses.isEmpty() || entityTypes.isEmpty()) {
            return false;
        }

        // Collections.disjoint returns true if the two sets have NO elements in common.
        // If they are NOT disjoint, they share an element, meaning we found a conflict.
        return !Collections.disjoint(entityTypes, precomputedDisjointClasses);
    }
}

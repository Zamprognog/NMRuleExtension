package ruleMiningSemanticExtension.groundingEngine;

import ruleMiningSemanticExtension.graphTools.Graph;
import ruleMiningSemanticExtension.graphTools.SemanticGraphManager;
import ruleMiningSemanticExtension.domain.GroundingResult;
import ruleMiningSemanticExtension.domain.Triple;

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

    @Override
    protected boolean isQueryValid(String queryNodeStr, String targetRelationStr, Boolean predictObject) {
        int queryNodeId = entityDict.lookup(queryNodeStr);
        int headRelationId = relationDict.lookup(targetRelationStr);

        if (headRelationId == -1 || queryNodeId == -1) {
            return true; 
        }

        SemanticGraphManager.IntPropertyConstraint propertyConstraint = propertyIdConstraints.get(headRelationId);
        if (propertyConstraint == null) {
            return true; 
        }
        SemanticGraphManager.IntPropertyConstraint targetRelationConstraints = propertyIdConstraints.get(headRelationId);

        Set<Integer> queryNodeTypes = entityIdTypes.getOrDefault(queryNodeId, Collections.emptySet());

        if (predictObject) {
            if (hasDisjointConflict(queryNodeTypes, targetRelationConstraints.disjointWithDomain)) return false;
            if (propertyConstraint.isFunctional) {
                int[] existingTargets = graph.getForwardTargets(queryNodeId, headRelationId);
                return existingTargets == null || existingTargets.length <= 0;
            }
        } else {
            if (hasDisjointConflict(queryNodeTypes, targetRelationConstraints.disjointWithRange)) return false;
            if (propertyConstraint.isInverseFunctional) {
                int[] existingSubjects = graph.getBackwardTargets(queryNodeId, headRelationId);
                return existingSubjects == null || existingSubjects.length <= 0;
            }
        }

        return true; 
    }

    @Override
    protected boolean checkSuccess(Graph graph, int startNodeId, String startVarName, String headVariable, int[] bindings, String[] varNames, List<GroundingResult> ruleGroundings, List<Triple> triples, int targetRelationId, Boolean predictObject) {

        if (headVariable == null) return true; 

        int headVariableIndex = IntStream.range(0, varNames.length)
                .filter(i -> varNames[i] != null && varNames[i].equals(headVariable))
                .findFirst()
                .orElse(-1);

        int predictedEntityId = (headVariableIndex == -1) ? startNodeId : bindings[headVariableIndex];

        // 1. Process the standard success binding
        Map<String, String> successfulBinding = new HashMap<>();

        if (startVarName != null && startVarName.length() == 1) {
            successfulBinding.put(startVarName, entityDict.getString(startNodeId));
        }
        
        boolean headVarInBindings = (headVariableIndex != -1);
        if (!headVarInBindings && headVariable != null && headVariable.length() == 1 && this.entityDict.lookup(headVariable) == -1) {
             successfulBinding.put(headVariable, entityDict.getString(startNodeId));
        }

        for (int i = 0; i < bindings.length; i++) {
            if (varNames[i] != null && bindings[i] != -1) {
                successfulBinding.put(varNames[i], entityDict.getString(bindings[i]));
            }
        }

        // Add the predicted triple to the path triples list
        String s = entityDict.getString(predictObject ? startNodeId : predictedEntityId);
        String p = relationDict.getString(targetRelationId);
        String o = entityDict.getString(predictObject ? predictedEntityId : startNodeId);
        triples.add(new Triple(s, p, o));

        // Compute 1-hop neighborhood stats using integer IDs — no string allocation
        Set<Integer> nodeIds = new HashSet<>();
        int neighborhoodEdgeCount = 0;
        Set<Integer> entities = new HashSet<>();
        entities.add(startNodeId);
        for (int b : bindings) if (b != -1) entities.add(b);
        for (int entityId : entities) {
            neighborhoodEdgeCount += addNeighborhood(entityId, nodeIds);
        }

        GroundingResult currentResult = new GroundingResult(successfulBinding, triples, neighborhoodEdgeCount, nodeIds);
        ruleGroundings.add(currentResult);

        // 2. CHECK SEMANTIC CONSTRAINTS
        SemanticGraphManager.IntPropertyConstraint targetRelationConstraints = propertyIdConstraints.get(targetRelationId);

        if (targetRelationConstraints != null) {
            if (predictObject) {
                if (targetRelationConstraints.isFunctional && countUniquePredictions(ruleGroundings, headVariable) > 1) {
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
                if (targetRelationConstraints.isInverseFunctional && countUniquePredictions(ruleGroundings, headVariable) > 1) {
                    ruleGroundings.clear();
                    return false;
                }
            }

            Set<Integer> predictedNodeTypes = entityIdTypes.getOrDefault(predictedEntityId, Collections.emptySet());

            if (!predictedNodeTypes.isEmpty()) {
                if (predictObject) {
                    if (hasDisjointConflict(predictedNodeTypes, targetRelationConstraints.disjointWithRange)) {
                        ruleGroundings.clear();
                        return false;
                    }
                } else {
                    if (hasDisjointConflict(predictedNodeTypes, targetRelationConstraints.disjointWithDomain)) {
                        ruleGroundings.clear();
                        return false;
                    }
                }
            }
        }

        return true;
    }

    private int addNeighborhood(int entityId, Set<Integer> nodeIds) {
        nodeIds.add(entityId);
        int[] count = {0};

        graph.forEachEdge(entityId, true, (relId, targetIds) -> {
            for (int tId : targetIds) {
                if (count[0] >= maxDegree) return;
                nodeIds.add(tId);
                count[0]++;
            }
        });

        graph.forEachEdge(entityId, false, (relId, sourceIds) -> {
            for (int sId : sourceIds) {
                if (count[0] >= maxDegree) return;
                nodeIds.add(sId);
                count[0]++;
            }
        });

        return count[0];
    }

    private long countUniquePredictions(List<GroundingResult> groundings, String headVariable) {
        return groundings.stream()
                .map(g -> g.bindings().get(headVariable))
                .filter(Objects::nonNull)
                .distinct()
                .count();
    }

    @Override
    protected boolean checkStartingNodeSuccess(Graph graph, int uniqueEntitiesCount, int knownEntityId, int headRelationId, int startNodeId, Boolean predictObject) {
        SemanticGraphManager.IntPropertyConstraint targetRelationConstraints = propertyIdConstraints.get(headRelationId);

        if (targetRelationConstraints != null) {
            if (predictObject) {
                if (targetRelationConstraints.isFunctional && uniqueEntitiesCount > 1) return false;
            } else {
                if (targetRelationConstraints.isFunctional) {
                    int[] existingTargets = graph.getForwardTargets(startNodeId, headRelationId);
                    if (existingTargets != null && existingTargets.length > 0) return false;
                }
                if (targetRelationConstraints.isInverseFunctional && uniqueEntitiesCount > 1) return false;
            }

            Set<Integer> predictedNodeTypes = entityIdTypes.getOrDefault(startNodeId, Collections.emptySet());

            if (!predictedNodeTypes.isEmpty()) {
                if (predictObject) {
                    if (hasDisjointConflict(predictedNodeTypes, targetRelationConstraints.disjointWithRange)) return false;
                } else {
                    if (hasDisjointConflict(predictedNodeTypes, targetRelationConstraints.disjointWithDomain)) return false;
                }
            }
        }
        return true;
    }

    private boolean hasDisjointConflict(Set<Integer> entityTypes, Set<Integer> precomputedDisjointClasses) {
        if (precomputedDisjointClasses.isEmpty() || entityTypes.isEmpty()) {
            return false;
        }
        return !Collections.disjoint(entityTypes, precomputedDisjointClasses);
    }
}

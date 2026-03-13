package evolveAggregation.groundingEngine;

import evolveAggregation.domain.Direction;
import evolveAggregation.domain.RuleStep;
import evolveAggregation.optimizedGraph.Graph;
import evolveAggregation.optimizedGraph.GraphDictionary;

import java.util.*;

public class SemanticGroundingEngine extends GroundingEngine{

    private final Map<Integer, Set<Integer>> entityIdTypes;
    private final Map<Integer, SemanticGraphManager.IntPropertyConstraint> propertyIdConstraints;

    public SemanticGroundingEngine(SemanticGraphManager gm) {
        super(gm.getGraph(), gm.getEntityDict(), gm.getRelationDict());
        this.entityIdTypes = gm.getEntityIdTypes();
        this.propertyIdConstraints = gm.getPropertyIdConstraints();
    }

    @Override
    protected boolean checkSuccess(int startNodeId, int[] bindings, String[] varNames, List<Map<String, String>> ruleGroundings, int targetRelationId, Boolean predictObject) {

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

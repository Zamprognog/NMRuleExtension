package evolveAggregation.groundingEngine;

import evolveAggregation.domain.Direction;
import evolveAggregation.domain.RuleStep;
import evolveAggregation.optimizedGraph.GraphDictionary;
import evolveAggregation.optimizedGraph.Graph;

import java.util.*;

/**
 * The {@code GroundingEngine} provides the core logic for grounding rules in an optimized graph.
 * It searches the graph for paths that satisfy a given sequence of rule steps and provides
 * bindings for the variables defined in those rules.
 */
public class GroundingEngine {

    /** The graph structure where the search is performed. */
    protected final Graph graph;
    /** Dictionary mapping entity IDs to their string representations. */
    protected final GraphDictionary entityDict;
    /** Dictionary mapping relation IDs to their string representations. */
    protected final GraphDictionary relationDict;

    /**
     * Constructs a new {@code GroundingEngine} with the required graph and dictionaries.
     *
     * @param graph the graph structure containing entity and relation triples
     * @param entityDict the dictionary for entity ID-to-string conversion
     * @param relationDict the dictionary for relation ID-to-string conversion
     */
    public GroundingEngine(Graph graph, GraphDictionary entityDict, GraphDictionary relationDict) {
        this.graph = graph;
        this.entityDict = entityDict;
        this.relationDict = relationDict;
    }

    /**
     * Finds all possible bindings for a rule starting from a specific node.
     *
     * @param startNodeStr the string representation of the starting node
     * @param stringSteps the list of rule steps to be followed from the start node
     * @param targetRelation the name of the relation being predicted
     * @param predictObject {@code true} if predicting the object, {@code false} if predicting the subject
     * @return a list of maps, where each map represents a set of variable bindings that satisfy the rule
     */
    public List<Map<String, String>> findBindings(String startNodeStr, List<RuleStep> stringSteps, String targetRelation, Boolean predictObject) {
        List<Map<String, String>> ruleGroundings = new ArrayList<>();

        int startNodeId = entityDict.lookup(startNodeStr);
        if (startNodeId == -1) {
            return ruleGroundings;
        }

        // --- Pre-compile rule steps into primitive IDs for faster lookup ---
        int numSteps = stringSteps.size();
        int[] stepRelations = new int[numSteps];
        int[] stepLiteralTargets = new int[numSteps];
        int targetRelationId = relationDict.lookup(targetRelation);

        Direction[] stepDirections = new Direction[numSteps];
        String[] stepVarNames = new String[numSteps];

        for (int i = 0; i < numSteps; i++) {
            RuleStep step = stringSteps.get(i);
            stepRelations[i] = relationDict.lookup(step.predicate);
            stepDirections[i] = step.direction;
            stepVarNames[i] = step.targetVarName;

            if (step.targetLiteral != null) {
                stepLiteralTargets[i] = entityDict.lookup(step.targetLiteral);
            } else {
                stepLiteralTargets[i] = -1;
            }
        }

        // --- Setup binding array for recursive search ---
        int[] currentBindings = new int[numSteps];
        Arrays.fill(currentBindings, -1);

        // --- Execute the recursive search ---
        doSearch(startNodeId, startNodeId, stepRelations, stepDirections, stepLiteralTargets, stepVarNames,
                0, currentBindings, ruleGroundings, targetRelationId, predictObject);

        return ruleGroundings;
    }

    /**
     * Performs a depth-first search (DFS) through the graph to find paths that satisfy the rule steps.
     *
     * @param startNodeId the ID of the original starting node
     * @param currentNode the ID of the node currently being processed in the search
     * @param relations the pre-compiled relation IDs for each rule step
     * @param dirs the directions (forward/backward) for each rule step
     * @param literalTargets the ID of the literal target for each step, or -1 if the target is a variable
     * @param varNames the variable names to be bound at each step
     * @param stepIndex the current step index in the rule (depth of the search)
     * @param bindings the current state of variable bindings
     * @param ruleGroundings the list where successful groundings are stored
     * @param targetRelationId the ID of the relation being predicted
     * @param predictObject {@code true} if predicting the object, {@code false} if predicting the subject
     * @return {@code true} to continue the search across other branches, {@code false} to abort
     */
    private boolean doSearch(int startNodeId, int currentNode, int[] relations, Direction[] dirs, int[] literalTargets,
                             String[] varNames, int stepIndex, int[] bindings,
                             List<Map<String, String>> ruleGroundings, int targetRelationId, Boolean predictObject) {

        // --- Base case: reached the end of the rule steps ---
        if (stepIndex == relations.length) {
            return checkSuccess(startNodeId, bindings, varNames, ruleGroundings, targetRelationId, predictObject);
        }

        // --- Forward case: look for groundings of the next step
        int relId = relations[stepIndex];
        if (relId == -1) {
            return true; // Relation isn't found in the graph; dead end this branch but continue searching for others
        }

        int[] targets = (dirs[stepIndex] == Direction.FORWARD) ?
                graph.getForwardTargets(currentNode, relId) :
                graph.getBackwardTargets(currentNode, relId);

        if (targets == null) {
            return true; // No targets found; dead end this branch but continue searching for others
        }

        for (int nextNode : targets) {
            // Iterate over candidate target nodes

            if (literalTargets[stepIndex] != -1) {
                // Analyzing an atom with a literal: check if the target matches the required literal
                if (nextNode != literalTargets[stepIndex]) {
                    continue;
                }
            } else {
                // Analyzing an atom with variables: ensure the variable value is unique in the current path
                if (containsValue(bindings, nextNode, stepIndex)) {
                    continue;
                }
                bindings[stepIndex] = nextNode;
            }

            // RECURSE: Move to the next step
            boolean continueSearch = doSearch(startNodeId, nextNode, relations, dirs, literalTargets, varNames,
                    stepIndex + 1, bindings, ruleGroundings, targetRelationId, predictObject);

            // BACKTRACK: Reset the binding for this step if it was a variable
            if (literalTargets[stepIndex] == -1) {
                bindings[stepIndex] = -1;
            }

            if (!continueSearch) {
                return false;
            }
        }

        return true; // Finished this branch normally
    }

    /**
     * Checks if a completed path through the graph constitutes a successful rule grounding.
     * This method can be overridden in subclasses to apply additional constraints.
     *
     * @param startNodeId the ID of the starting node
     * @param bindings the array of node IDs bound to each step in the rule
     * @param varNames the array of variable names corresponding to each step
     * @param ruleGroundings the list to which the successful binding should be added
     * @param targetRelationId the ID of the relation being predicted
     * @param predictObject {@code true} if predicting the object, {@code false} if predicting the subject. Not used in this Class
     * @return {@code true} to continue searching for more groundings, {@code false} to stop
     */
    protected boolean checkSuccess(int startNodeId, int[] bindings, String[] varNames, List<Map<String, String>> ruleGroundings, int targetRelationId, Boolean predictObject) {
        Map<String, String> successfulBinding = new HashMap<>();
        for (int i = 0; i < bindings.length; i++) {
            if (varNames[i] != null && bindings[i] != -1) {
                successfulBinding.put(varNames[i], entityDict.getString(bindings[i]));
            }
        }
        ruleGroundings.add(successfulBinding);

        return true; // Base class has no additional constraints, so always continue searching
    }

    /**
     * Returns the total number of entities in the graph dictionary.
     *
     * @return the size of the entity dictionary
     */
    public int entityCount() {
        return entityDict.size();
    }

    /**
     * Checks if a given value is already present in the bindings up to the current index.
     * Used to enforce that different variables in a rule path map to different entities.
     *
     * @param bindings the array of current bindings
     * @param value the value to check for
     * @param currentIndex the current step index
     * @return {@code true} if the value is already bound, {@code false} otherwise
     */
    protected boolean containsValue(int[] bindings, int value, int currentIndex) {
        for (int i = 0; i < currentIndex; i++) {
            if (bindings[i] == value) {
                return true;
            }
        }
        return false;
    }

    /**
     * Used by Unary rules when the start node is a variable.
     * Iterates over ALL nodes in the graph to find those that satisfy the rule body.
     *
     * @param stringSteps the list of rule steps
     * @param targetRelation the name of the relation being predicted
     * @param predictObject {@code true} if predicting the object, {@code false} if predicting the subject
     * @return a list of node strings that satisfy the rule
     */
    public List<String> findSatisfyingStartNodes(List<RuleStep> stringSteps, String targetRelation, Boolean predictObject) {
        List<String> validStartNodes = new ArrayList<>();

        // 1. Pre-compile rule steps into primitives
        int numSteps = stringSteps.size();
        int[] stepRelations = new int[numSteps];
        int[] stepLiteralTargets = new int[numSteps];
        int targetRelationId = relationDict.lookup(targetRelation);
        Direction[] stepDirections = new Direction[numSteps];
        String[] stepVarNames = new String[numSteps];

        for (int i = 0; i < numSteps; i++) {
            RuleStep step = stringSteps.get(i);
            stepRelations[i] = relationDict.lookup(step.predicate);
            stepDirections[i] = step.direction;
            stepVarNames[i] = step.targetVarName;

            stepLiteralTargets[i] = (step.targetLiteral != null) ?
                    entityDict.lookup(step.targetLiteral) : -1;
        }

        // If the rule uses a relation that isn't in our dictionary, no node will satisfy it
        for (int relId : stepRelations) {
            if (relId == -1) {
                return validStartNodes;
            }
        }

        int totalNodes = entityDict.size();

        // 2. Optimization: reuse objects to avoid heavy O(N) allocations inside the hot loop
        int[] currentBindings = new int[numSteps];
        Arrays.fill(currentBindings, -1);
        List<Map<String, String>> dummyResults = new ArrayList<>();

        // 3. Loop over every node ID in the graph
        for (int startNodeId = 0; startNodeId < totalNodes; startNodeId++) {

            // Check if the node has at least one edge for the first relation
            int firstRel = stepRelations[0];
            int[] initialTargets = (stepDirections[0] == Direction.FORWARD) ?
                    graph.getForwardTargets(startNodeId, firstRel) :
                    graph.getBackwardTargets(startNodeId, firstRel);

            if (initialTargets == null) {
                continue; // Node doesn't have the required edge; skip
            }

            dummyResults.clear(); // Safe to reuse; bindings are reset to -1 via engine backtracking

            doSearch(startNodeId, startNodeId, stepRelations, stepDirections, stepLiteralTargets, stepVarNames,
                    0, currentBindings, dummyResults, targetRelationId, predictObject);

            // If the search yielded results, this node satisfies the rule!
            if (!dummyResults.isEmpty()) {
                validStartNodes.add(entityDict.getString(startNodeId));
            }
        }

        return validStartNodes;
    }
    /**
     * Converts an entity string to its corresponding ID.
     *
     * @param entity the string representation of an entity
     * @return the ID of the entity, or -1 if not found
     */
    public int entityToId(String entity) {
        return entityDict.lookup(entity);
    }

    /**
     * Converts an entity ID back to its string representation.
     *
     * @param id the ID of the entity
     * @return the string representation of the entity
     */
    public String idToEntity(int id) {
        return entityDict.getString(id);
    }
}

package evolveAggregation.groundingEngine;

import evolveAggregation.rules.Direction;
import evolveAggregation.rules.RulePatternStep;
import evolveAggregation.rules.RulePathStep;
import evolveAggregation.graphTools.GraphDictionary;
import evolveAggregation.graphTools.Graph;

import java.util.*;

/**
 * The {@code GroundingEngine} provides the core logic for grounding rules.
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
     * Hook for subclasses to perform early validation on the query node and relation.
     * Overridden by SemanticGroundingEngine.
     */
    protected boolean isQueryValid(String queryNodeStr, String targetRelationStr, Boolean predictObject) {
        return true; // Standard engine has no constraints, always proceed.
    }

    /**
     * Finds all possible bindings for a rule starting from a specific node. Applies to Anyburl-type rules.
     *
     * @param startNodeStr the string representation of the starting node
     * @param stringSteps the list of rule steps to be followed from the start node
     * @param targetRelation the name of the relation being predicted
     * @param predictObject {@code true} if predicting the object, {@code false} if predicting the subject
     * @return a list of maps, where each map represents a set of variable bindings that satisfy the rule
     */
    public List<Map<String, String>> findPathGroundings(String startNodeStr, List<RulePathStep> stringSteps, String targetRelation, Boolean predictObject) {
        // PRUNE EARLY: Check if the query itself is valid before searching
        if (!isQueryValid(startNodeStr, targetRelation, predictObject)) {
            return new ArrayList<>();
        }

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
            RulePathStep step = stringSteps.get(i);
            stepRelations[i] = relationDict.lookup(step.predicate);
            stepDirections[i] = step.direction;
            stepVarNames[i] = step.targetVarName;

            if (step.targetLiteral != null) {
                stepLiteralTargets[i] = entityDict.lookup(step.targetLiteral);
            } else {
                stepLiteralTargets[i] = -1;
            }
        }

        // --- Set up a binding array for recursive search ---
        int[] currentBindings = new int[numSteps];
        Arrays.fill(currentBindings, -1);

        // --- Execute the recursive search ---
        doPathSearch(startNodeId, startNodeId, stepRelations, stepDirections, stepLiteralTargets, stepVarNames,
                0, currentBindings, ruleGroundings, targetRelationId, predictObject);

        return ruleGroundings;
    }

    /**
     * Finds groundings using Subgraph Isomorphism for AMIE-style rules.
     * This method is used when the rule is defined as a general graph pattern rather than a simple path.
     *
     * @param startNodeString The entity string we are starting from (e.g., "e1")
     * @param startVarName The variable name of the start node (e.g., "A")
     * @param targetVarName The variable name we want to predict (e.g., "B")
     * @param steps The ordered list of graph pattern steps
     * @return A list of valid entity strings that bind to the targetVarName
     */
    public List<String> findPatternGroundings(String startNodeString, String startVarName, String targetVarName, List<RulePatternStep> steps) {
        int startId = entityToId(startNodeString);
        if (startId == -1) return new ArrayList<>();

        List<Integer> validTargetIds = new ArrayList<>();

        // The bindings map keeps track of which variable is bound to which Node ID
        Map<String, Integer> bindings = new HashMap<>();
        bindings.put(startVarName, startId);

        doPatternSearch(steps, 0, bindings, validTargetIds, targetVarName);

        // Convert resulting IDs back to Strings
        List<String> results = new ArrayList<>();
        for (int id : validTargetIds) {
            results.add(idToEntity(id));
        }
        return results;
    }

    /**
     * Used by Anyburl-style Unary rules when the start node is a variable.
     * Iterates over ALL nodes in the graph to find those that satisfy the rule body.
     *
     * @param stringSteps the list of rule steps
     * @param targetRelation the name of the relation being predicted
     * @param predictObject {@code true} if predicting the object, {@code false} if predicting the subject
     * @param knownEntity the known entity in the query triple, needed in subclasses for semantic checks
     * @return a list of node strings that satisfy the rule
     */
//    public List<String> findSatisfyingStartNodes(List<RuleStep> stringSteps, String targetRelation, Boolean predictObject) {
    public List<String> findSatisfyingStartNodes(List<RulePathStep> stringSteps, String targetRelation, Boolean predictObject, String knownEntity) {
        List<String> validStartNodes = new ArrayList<>();


        // PRUNE ENTIRE LOOP EARLY: No need to evaluate all nodes if the query node violates constraints
        if (!isQueryValid(knownEntity, targetRelation, predictObject)) {
            return new ArrayList<>();
        }

        // 1. Pre-compile rule steps into primitives
        int numSteps = stringSteps.size();
        int[] stepRelations = new int[numSteps];
        int[] stepLiteralTargets = new int[numSteps];
        int targetRelationId = relationDict.lookup(targetRelation);
        Direction[] stepDirections = new Direction[numSteps];
        String[] stepVarNames = new String[numSteps];

        for (int i = 0; i < numSteps; i++) {
            RulePathStep step = stringSteps.get(i);
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

            doPathSearch(startNodeId, startNodeId, stepRelations, stepDirections, stepLiteralTargets, stepVarNames,
                    0, currentBindings, dummyResults, targetRelationId, predictObject);

            // If the search yielded results, this node satisfies the rule!
            if (!dummyResults.isEmpty()) {
                validStartNodes.add(entityDict.getString(startNodeId));
            }
        }

        return validStartNodes;
    }

    /**
     * Performs a depth-first search (DFS) through the graph to find paths that satisfy the rule steps.
     * Each step in the rule corresponds to a triple (subject, relation, object) in the graph.
     * This method handles both literal targets and variables, ensuring that variables in a path
     * are bound uniquely to prevent cycles or redundant groundings within a single rule application.
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
    private boolean doPathSearch(int startNodeId, int currentNode, int[] relations, Direction[] dirs, int[] literalTargets,
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
            boolean continueSearch = doPathSearch(startNodeId, nextNode, relations, dirs, literalTargets, varNames,
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
     * Performs a depth-first search (DFS) to find all satisfying groundings for an AMIE-style graph pattern.
     * This method recursively explores the graph, binding variables defined in the {@link RulePatternStep} list.
     *
     * @param steps The ordered list of graph pattern steps to satisfy.
     * @param stepIndex The current step index in the pattern (depth of the search).
     * @param bindings A map of variable names to their current entity ID bindings.
     * @param results A list to store the entity IDs that satisfy the target variable.
     * @param targetVarName The name of the variable whose satisfying entity IDs we want to collect.
     */
    private void doPatternSearch(List<RulePatternStep> steps, int stepIndex, Map<String, Integer> bindings, List<Integer> results, String targetVarName) {
        // Base case: We successfully mapped all steps in the rule
        //todo: integrate the semantic part (@checksuccess etc)
        if (stepIndex == steps.size()) {
            if (bindings.containsKey(targetVarName)) {
                results.add(bindings.get(targetVarName));
            }
            return;
        }

        RulePatternStep step = steps.get(stepIndex);

        int sourceId = bindings.get(step.sourceVarName);
        int relationId = relationDict.lookup(step.predicate);
        if (relationId == -1) return; // Relation doesn't exist in graph

        int[] neighbors = step.direction == Direction.FORWARD ? graph.getForwardTargets(sourceId, relationId) : graph.getBackwardTargets(sourceId, relationId);

        if (neighbors == null) return;

        for (int neighborId : neighbors) {

            // CASE 1: Loop Closure - The target variable is ALREADY bound
            if (bindings.containsKey(step.targetVarName)) {
                if (bindings.get(step.targetVarName) != neighborId) continue; // Must match the existing binding

                doPatternSearch(steps, stepIndex + 1, bindings, results, targetVarName);
            }
            // CASE 2: Standard Traversal - The target variable is NEW
            else {
                // Bind it, recurse, then backtrack
                bindings.put(step.targetVarName, neighborId);
                doPatternSearch(steps, stepIndex + 1, bindings, results, targetVarName);
                bindings.remove(step.targetVarName); // Backtrack!
            }
        }
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

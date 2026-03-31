package evolveAggregation.groundingEngine;

import evolveAggregation.graphTools.GraphManager;
import evolveAggregation.rules.Direction;
import evolveAggregation.rules.RulePatternStep;
import evolveAggregation.rules.RulePathStep;
import evolveAggregation.graphTools.GraphDictionary;
import evolveAggregation.graphTools.Graph;

import java.util.*;
import java.util.stream.IntStream;

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
    protected final int maxBranchCount;

    /**
     * Constructs a new {@code GroundingEngine} with the required graph and dictionaries.
     *
     * @param gm the graph manager containing the graph and dictionaries.
     */
    public GroundingEngine(GraphManager gm) {
        this.graph = gm.getGraph();
        this.entityDict = gm.getEntityDict();
        this.relationDict = gm.getRelationDict();
        this.maxBranchCount = 10000;
    }

    // AnyBURL rules section.

    /**
     * Finds all possible bindings for a rule starting from a specific node. Applies to Anyburl-type rules.
     *
     * @param startNodeStr the string representation of the starting node
     * @param stringSteps the list of rule steps to be followed from the start node
     * @param headRelation the name of the relation being predicted
     * @param headVariable the name of the variable in the head for which the grounding is being computed
     * @param predictObject {@code true} if predicting the object, {@code false} if predicting the subject
     * @return a list of maps, where each map represents a set of variable bindings that satisfy the rule
     */
    public List<Map<String, String>> findPathGroundings(String startNodeStr, List<RulePathStep> stringSteps, String headRelation, String headVariable, Boolean predictObject) {
        // PRUNE EARLY: Check if the query itself is valid before searching
        if (!isQueryValid(startNodeStr, headRelation, predictObject)) {
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
        int headRelationId = relationDict.lookup(headRelation);

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
        int[] branchCounter = {this.maxBranchCount};
        // --- Execute the recursive search ---
        doPathSearch(startNodeId, startNodeId, stepRelations, stepDirections, stepLiteralTargets, stepVarNames,
                0, currentBindings, ruleGroundings, headRelationId, headVariable, predictObject, branchCounter);

        return ruleGroundings;
    }

    /**
     * Used by Anyburl-style Unary rules when the start node is a variable.
     * Iterates over ALL nodes in the graph to find those that satisfy the rule body.
     *
     * @param stringSteps the list of rule steps
     * @param headRelation the name of the relation being predicted
     * @param predictObject {@code true} if predicting the object, {@code false} if predicting the subject
     * @param knownEntity the known entity in the query triple, needed in subclasses for semantic checks
     * @return a list of node strings that satisfy the rule
     */
    public List<String> findSatisfyingStartNodes(List<RulePathStep> stringSteps, String headRelation, String headVariable, Boolean predictObject, String knownEntity) {
        List<String> validStartNodes = new ArrayList<>();


        // PRUNE ENTIRE LOOP EARLY: No need to evaluate all nodes if the query node violates constraints
        if (!isQueryValid(knownEntity, headRelation, predictObject)) {
            return new ArrayList<>();
        }

        // 1. Pre-compile rule steps into primitives
        int numSteps = stringSteps.size();
        int[] stepRelations = new int[numSteps];
        int[] stepLiteralTargets = new int[numSteps];
        int headRelationId = relationDict.lookup(headRelation);
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

        int[] branchCounter = {this.maxBranchCount};
        // 3. Loop over every node ID in the graph
        for (int startNodeId = 0; startNodeId < totalNodes; startNodeId++) {

            if (branchCounter[0] <= 0) {
                break; // Stop evaluating new start nodes if the global limit is reached
            }
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
                    0, currentBindings, dummyResults, headRelationId, null, predictObject, branchCounter);

            // If the search yielded results, this node satisfies the rule!
            if (!dummyResults.isEmpty()) {
                validStartNodes.add(entityDict.getString(startNodeId));
            }
            if (!checkStartingNodeSuccess(graph, validStartNodes, this.entityDict.lookup(knownEntity), headRelationId, startNodeId, predictObject)) {
                //semantic check failed
                return new ArrayList<>();
            }
        }

        return validStartNodes;
    }

    /**
     * Checks if a completed path through the graph constitutes a successful rule grounding.
     * This method can be overridden in subclasses to apply additional constraints.
     *
     * @param graph the graph instance
     * @param validStartNodes the current list of starting nodes (rules groundings)
     * @param knownEntityId the ID of the known entity in the head of the unary rule
     * @param headRelationId the ID of the relation in the head of the rule
     * @param startNodeId the ID of the starting node (and rule variable binding) that was just grounded
     * @param predictObject {@code true} if predicting the object, {@code false} if predicting the subject. Not used in this Class
     * @return {@code true} to continue searching for more groundings, {@code false} to stop
     */
    protected boolean checkStartingNodeSuccess(Graph graph, List<String> validStartNodes, int knownEntityId, int headRelationId, int startNodeId, Boolean predictObject) {
        return true;
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
     * @param headRelationId the ID of the relation being predicted
     * @param predictObject {@code true} if predicting the object, {@code false} if predicting the subject
     * @return {@code true} to continue the search across other branches, {@code false} to abort
     */
    private boolean doPathSearch(int startNodeId, int currentNode, int[] relations, Direction[] dirs, int[] literalTargets,
                                 String[] varNames, int stepIndex, int[] bindings,
                                 List<Map<String, String>> ruleGroundings, int headRelationId, String headVariable, Boolean predictObject, int[] branchCounter) {

        // --- Base case: reached the end of the rule steps ---
        if (stepIndex == relations.length) {
            return checkSuccess(graph,startNodeId, headVariable, bindings, varNames, ruleGroundings, headRelationId, predictObject);
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
            if (branchCounter[0] <= 0) {
                return true; //branch limit reached
            }
            branchCounter[0]--;

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
                    stepIndex + 1, bindings, ruleGroundings, headRelationId, headVariable, predictObject, branchCounter);

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
     * Finds groundings using Subgraph Isomorphism for AMIE-style rules.
     * This method is used when the rule is defined as a general graph pattern rather than a simple path.
     *
     * @param startNodeStr The entity string we are starting from (e.g., "e1")
     * @param steps The ordered list of rule pattern steps
     * @param headRelation The relation in the head of the rule
     * @param startVarName The variable name of the start node (e.g., "A")
     * @param headVariable The variable name we want to predict (e.g., "B")
     * @param predictObject  {@code true} if predicting the object, {@code false} if predicting the subject
     *
     * @return a list of maps, where each map represents a set of variable bindings that satisfy the rule
     */
    public List<Map<String, String>> findPatternGroundings(String startNodeStr, List<RulePatternStep> steps, String headRelation, String startVarName, String headVariable, Boolean predictObject) {
        if (!isQueryValid(startNodeStr, headRelation, predictObject)) {
            return new ArrayList<>();
        }

        List<Map<String, String>> results = new ArrayList<>();
        int startNodeId = entityDict.lookup(startNodeStr);
        if (startNodeId == -1) {
            return results;
        }

        // 1. Map string variable names to fixed indices for our bindings array
        Map<String, Integer> varToIndex = new HashMap<>();
        List<String> indexToVar = new ArrayList<>();

        // Ensure the start and target variables exist in our mapping
        varToIndex.put(startVarName, 0);
        indexToVar.add(startVarName);

        if (!varToIndex.containsKey(headVariable)) {
            varToIndex.put(headVariable, varToIndex.size());
            indexToVar.add(headVariable);
        }

        for (RulePatternStep step : steps) {
            if (!varToIndex.containsKey(step.sourceVarName)) {
                varToIndex.put(step.sourceVarName, varToIndex.size());
                indexToVar.add(step.sourceVarName);
            }
            if (step.targetVarName != null && !varToIndex.containsKey(step.targetVarName)) {
                varToIndex.put(step.targetVarName, varToIndex.size());
                indexToVar.add(step.targetVarName);
            }
        }

        // 2. Pre-compile rule steps to primitives for fast traversal
        int numSteps = steps.size();
        int[] relations = new int[numSteps];
        Direction[] dirs = new Direction[numSteps];
        int[] sourceVarIndices = new int[numSteps];
        int[] targetVarIndices = new int[numSteps];
        int targetRelationId = relationDict.lookup(headRelation);

        for (int i = 0; i < numSteps; i++) {
            RulePatternStep step = steps.get(i);
            relations[i] = relationDict.lookup(step.predicate);
            dirs[i] = step.direction;
            sourceVarIndices[i] = varToIndex.get(step.sourceVarName);
            targetVarIndices[i] = step.targetVarName != null ? varToIndex.get(step.targetVarName) : -1;
        }

        // 3. Set up the bindings array and bind the start node
        int[] bindings = new int[varToIndex.size()];
        Arrays.fill(bindings, -1);
        bindings[varToIndex.get(startVarName)] = startNodeId;

        // Convert our index mapping to an array to pass into checkSuccess
        String[] varNames = indexToVar.toArray(new String[0]);
        int[] branchCounter = {this.maxBranchCount};
        // 4. Execute recursive search
        doPatternSearch(startNodeId, 0, relations, dirs, sourceVarIndices, targetVarIndices, varNames, bindings, results, targetRelationId, headVariable, predictObject, branchCounter);

        return results;
    }

    // AMIE Rules section.

    /**
     * Performs a depth-first search (DFS) to find all satisfying groundings for an AMIE-style graph pattern.
     * This method recursively explores the graph, binding variables defined in the {@link RulePatternStep} list.
     *
     * @param stepIndex The current step index in the pattern (depth of the search).
     * @param relations The pre-compiled list of relation IDs in steps.
     * @param dirs The pre-compiled list of directions (forward/backward) in steps.
     * @param sourceVarIndices The list of source variable indices in steps.
     * @param targetVarIndices The list of target variable indices in steps.
     * @param bindings A map of variable names to their current entity ID bindings.
     * @param results A list to store the entity IDs that satisfy the target variable.
     */
    private boolean doPatternSearch(int startNodeId, int stepIndex, int[] relations, Direction[] dirs,
                                    int[] sourceVarIndices, int[] targetVarIndices, String[] varNames, int[] bindings,
                                    List<Map<String, String>> results, int targetRelationId, String headVariable,
                                    Boolean predictObject, int[] branchCounter) {

        // Base case: We successfully mapped all steps
        if (stepIndex == relations.length) {
            return checkSuccess(graph,startNodeId, headVariable, bindings, varNames, results, targetRelationId, predictObject);
        }

        int relId = relations[stepIndex];
        if (relId == -1) {
            return true; // Relation isn't found in the graph, skip this branch
        }

        // The source node for this step is guaranteed to be bound based on buildOrderedPattern logic
        int sourceNodeId = bindings[sourceVarIndices[stepIndex]];

        // Fetch targets based on direction
        int[] targets = (dirs[stepIndex] == Direction.FORWARD) ?
                graph.getForwardTargets(sourceNodeId, relId) :
                graph.getBackwardTargets(sourceNodeId, relId);

        if (targets == null) {
            return true; // No edges found, skip
        }

        int targetVarIdx = targetVarIndices[stepIndex];

        for (int nextNode : targets) {
            if (branchCounter[0] <= 0) {
                return true; //branch limit reached
            }
            branchCounter[0]--;

            if (targetVarIdx != -1) {
                if (bindings[targetVarIdx] != -1) {
                    // CRITICAL LOGIC: The target variable is ALREADY bound.
                    // This means we are closing a loop in the graph pattern. The node MUST match the bound entity.
                    if (bindings[targetVarIdx] != nextNode) {
                        continue;
                    }

                    boolean continueSearch = doPatternSearch(startNodeId, stepIndex + 1, relations, dirs, sourceVarIndices, targetVarIndices, varNames, bindings, results, targetRelationId, headVariable,predictObject, branchCounter);
                    if (!continueSearch) return false;

                } else {
                    // Variable is not bound yet. Enforce injectivity (prevent mapping different variables to the same entity).
                    if (containsValueForPattern(bindings, nextNode)) {
                        continue;
                    }

                    // Bind the new variable and recurse
                    bindings[targetVarIdx] = nextNode;
                    boolean continueSearch = doPatternSearch(startNodeId, stepIndex + 1, relations, dirs, sourceVarIndices, targetVarIndices, varNames, bindings, results, targetRelationId, headVariable,predictObject, branchCounter);

                    // Backtrack
                    bindings[targetVarIdx] = -1;

                    if (!continueSearch) return false;
                }
            } else {
                // Target is not a variable (e.g., a literal). Skip variable binding and proceed.
                boolean continueSearch = doPatternSearch(startNodeId, stepIndex + 1, relations, dirs, sourceVarIndices, targetVarIndices, varNames, bindings, results, targetRelationId, headVariable,predictObject, branchCounter);
                if (!continueSearch) return false;
            }
        }
        return true;
    }

    /**
     * Checks if a given value is already present anywhere in the bindings array.
     * Unlike the standard path method, pattern arrays are populated out of order.
     */
    protected boolean containsValueForPattern(int[] bindings, int value) {
        for (int binding : bindings) {
            if (binding == value) {
                return true;
            }
        }
        return false;
    }

    // Useful methods section.

    /**
     * Hook for subclasses to perform early validation on the query node and relation.
     * Overridden by SemanticGroundingEngine.
     */
    protected boolean isQueryValid(String queryNodeStr, String targetRelationStr, Boolean predictObject) {
        return true; // Standard engine has no constraints, always proceed.
    }

    /**
     * Checks if a completed path through the graph constitutes a successful rule grounding.
     * This method can be overridden in subclasses to apply additional constraints.
     *
     * @param graph the graph instance
     * @param startNodeId the ID of the starting node
     * @param headVariable the name of the head variable being grounded
     * @param bindings the array of node IDs bound to each step in the rule
     * @param varNames the array of variable names corresponding to each step
     * @param ruleGroundings the list to which the successful binding should be added
     * @param targetRelationId the ID of the relation being predicted
     * @param predictObject {@code true} if predicting the object, {@code false} if predicting the subject. Not used in this Class
     * @return {@code true} to continue searching for more groundings, {@code false} to stop
     */
    protected boolean checkSuccess(Graph graph, int startNodeId,String headVariable, int[] bindings, String[] varNames, List<Map<String, String>> ruleGroundings, int targetRelationId, Boolean predictObject) {

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

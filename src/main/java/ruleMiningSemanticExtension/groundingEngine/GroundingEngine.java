package ruleMiningSemanticExtension.groundingEngine;

import ruleMiningSemanticExtension.domain.GroundingResult;
import ruleMiningSemanticExtension.domain.Triple;
import ruleMiningSemanticExtension.graphTools.GraphManager;
import ruleMiningSemanticExtension.rules.Direction;
import ruleMiningSemanticExtension.rules.RulePathStep;
import ruleMiningSemanticExtension.rules.RulePatternStep;
import ruleMiningSemanticExtension.graphTools.GraphDictionary;
import ruleMiningSemanticExtension.graphTools.Graph;

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
    protected final int maxBranchCount;
    protected int maxDegree = 10; // Default max degree for neighborhood expansion

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

    public void setMaxDegree(int maxDegree) {
        this.maxDegree = maxDegree;
    }

    // AnyBURL rules section.

    /**
     * Finds all possible bindings for a rule starting from a specific node. Applies to Anyburl-type rules.
     *
     * @param startNodeStr the string representation of the starting node
     * @param stringSteps the list of rule steps to be followed from the start node
     * @param headRelation the name of the relation being predicted
     * @param startVarName the name of the variable corresponding to the start node
     * @param headVariable the name of the variable in the head for which the grounding is being computed
     * @param predictObject {@code true} if predicting the object, {@code false} if predicting the subject
     * @return a list of maps, where each map represents a set of variable bindings that satisfy the rule
     */
    public List<GroundingResult> findPathGroundings(String startNodeStr, List<RulePathStep> stringSteps, String headRelation, String startVarName, String headVariable, Boolean predictObject) {
        // PRUNE EARLY: Check if the query itself is valid before searching
        if (!isQueryValid(startNodeStr, headRelation, predictObject)) {
            return new ArrayList<>();
        }

        List<GroundingResult> ruleGroundings = new ArrayList<>();

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
                0, currentBindings, ruleGroundings, headRelationId, startVarName, headVariable, predictObject, branchCounter);

        return ruleGroundings;
    }

    /**
     * Used by Anyburl-style Unary rules when the start node is a variable.
     * Iterates over ALL nodes in the graph to find those that satisfy the rule body.
     *
     * @param stringSteps the list of rule steps
     * @param headRelation the name of the relation being predicted
     * @param headVariable the name of the variable being predicted
     * @param predictObject {@code true} if predicting the object, {@code false} if predicting the subject
     * @param knownEntity the known entity in the query triple, needed in subclasses for semantic checks
     * @return a list of node strings that satisfy the rule
     */
    public List<GroundingResult> findSatisfyingStartNodes(List<RulePathStep> stringSteps, String headRelation, String headVariable, Boolean predictObject, String knownEntity) {
        List<GroundingResult> allGroundings = new ArrayList<>();
        Set<Integer> uniqueStartNodes = new HashSet<>();


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
                return allGroundings;
            }
        }

        int totalNodes = entityDict.size();

        // 2. Optimization: reuse objects to avoid heavy O(N) allocations inside the hot loop
        int[] currentBindings = new int[numSteps];
        Arrays.fill(currentBindings, -1);

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

            int initialResultsSize = allGroundings.size();

            doPathSearch(startNodeId, startNodeId, stepRelations, stepDirections, stepLiteralTargets, stepVarNames,
                    0, currentBindings, allGroundings, headRelationId, headVariable, headVariable, predictObject, branchCounter);

            // If the search yielded results, this node satisfies the rule!
            if (allGroundings.size() > initialResultsSize) {
                uniqueStartNodes.add(startNodeId);
                if (!checkStartingNodeSuccess(graph, uniqueStartNodes.size(), this.entityDict.lookup(knownEntity), headRelationId, startNodeId, predictObject)) {
                    //semantic check failed for THIS startNodeId, remove its groundings
                    uniqueStartNodes.remove(startNodeId);
                    while (allGroundings.size() > initialResultsSize) {
                        allGroundings.remove(allGroundings.size() - 1);
                    }
                }
            }
        }

        return allGroundings;
    }

    /**
     * Performs a depth-first search (DFS) through the graph to find paths that satisfy the rule steps.
     * Each step in the rule corresponds to a triple (subject, relation, object) in the graph.
     * This method handles both literal targets and variables, ensuring that variables in a path
     * are bound uniquely to prevent cycles or redundant groundings within a single rule application.
     */
    private boolean doPathSearch(int startNodeId, int currentNode, int[] relations, Direction[] dirs, int[] literalTargets,
                                 String[] varNames, int stepIndex, int[] bindings,
                                 List<GroundingResult> ruleGroundings, int headRelationId, String startVarName, String headVariable, Boolean predictObject, int[] branchCounter) {

        // --- Base case: reached the end of the rule steps ---
        if (stepIndex == relations.length) {
            // Reconstruct triples for this path
            List<Triple> triples = new ArrayList<>();
            int prevNode = startNodeId;
            for (int i = 0; i < relations.length; i++) {
                int nextNode = (literalTargets[i] != -1) ? literalTargets[i] : bindings[i];
                String s = entityDict.getString(prevNode);
                String p = relationDict.getString(relations[i]);
                String o = entityDict.getString(nextNode);
                if (dirs[i] == Direction.FORWARD) {
                    triples.add(new Triple(s, p, o));
                } else {
                    triples.add(new Triple(o, p, s));
                }
                prevNode = nextNode;
            }
            return checkSuccess(graph, startNodeId, startVarName, headVariable, bindings, varNames, ruleGroundings, triples, headRelationId, predictObject);
        }

        // --- Forward case: look for groundings of the next step
        int relId = relations[stepIndex];
        if (relId == -1) {
            return true;
        }

        int[] targets = (dirs[stepIndex] == Direction.FORWARD) ?
                graph.getForwardTargets(currentNode, relId) :
                graph.getBackwardTargets(currentNode, relId);

        if (targets == null) {
            return true;
        }

        for (int nextNode : targets) {
            if (branchCounter[0] <= 0) {
                return true;
            }
            branchCounter[0]--;

            if (literalTargets[stepIndex] != -1) {
                if (nextNode != literalTargets[stepIndex]) {
                    continue;
                }
            } else {
                if (containsValue(bindings, nextNode, stepIndex)) {
                    continue;
                }
                bindings[stepIndex] = nextNode;
            }

            boolean continueSearch = doPathSearch(startNodeId, nextNode, relations, dirs, literalTargets, varNames,
                    stepIndex + 1, bindings, ruleGroundings, headRelationId, startVarName, headVariable, predictObject, branchCounter);

            if (literalTargets[stepIndex] == -1) {
                bindings[stepIndex] = -1;
            }

            if (!continueSearch) {
                return false;
            }
        }

        return true;
    }


    /**
     * Finds groundings using Subgraph Isomorphism for AMIE-style rules.
     * This method is used when the rule is defined as a general graph pattern rather than a simple path.
     */
    public List<GroundingResult> findPatternGroundings(String startNodeStr, List<RulePatternStep> steps, String headRelation, String startVarName, String headVariable, Boolean predictObject) {
        if (!isQueryValid(startNodeStr, headRelation, predictObject)) {
            return new ArrayList<>();
        }

        List<GroundingResult> results = new ArrayList<>();
        int startNodeId = entityDict.lookup(startNodeStr);
        if (startNodeId == -1) {
            return results;
        }

        // 1. Map string variable names to fixed indices for our bindings array
        Map<String, Integer> varToIndex = new HashMap<>();
        List<String> indexToVar = new ArrayList<>();

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
        int[] literalTargets = new int[numSteps];
        int targetRelationId = relationDict.lookup(headRelation);

        for (int i = 0; i < numSteps; i++) {
            RulePatternStep step = steps.get(i);
            relations[i] = relationDict.lookup(step.predicate);
            dirs[i] = step.direction;
            sourceVarIndices[i] = varToIndex.get(step.sourceVarName);
            targetVarIndices[i] = step.targetVarName != null ? varToIndex.get(step.targetVarName) : -1;
            literalTargets[i] = step.targetLiteral != null ? entityDict.lookup(step.targetLiteral) : -1;
        }

        // 3. Set up the bindings array and bind the start node
        int[] bindings = new int[varToIndex.size()];
        Arrays.fill(bindings, -1);
        bindings[varToIndex.get(startVarName)] = startNodeId;

        String[] varNames = indexToVar.toArray(new String[0]);
        int[] branchCounter = {this.maxBranchCount};
        // 4. Execute recursive search
        doPatternSearch(startNodeId, 0, relations, dirs, sourceVarIndices, targetVarIndices, literalTargets, varNames, bindings, results, targetRelationId, startVarName, headVariable, predictObject, branchCounter);

        return results;
    }

    /**
     * Performs a depth-first search (DFS) to find all satisfying groundings for an AMIE-style graph pattern.
     */
    private boolean doPatternSearch(int startNodeId, int stepIndex, int[] relations, Direction[] dirs,
                                    int[] sourceVarIndices, int[] targetVarIndices, int[] literalTargets, String[] varNames, int[] bindings,
                                    List<GroundingResult> results, int targetRelationId, String startVarName, String headVariable,
                                    Boolean predictObject, int[] branchCounter) {

        if (stepIndex == relations.length) {
            // Reconstruct triples for this pattern
            List<Triple> triples = new ArrayList<>();
            for (int i = 0; i < relations.length; i++) {
                int sourceNodeId = bindings[sourceVarIndices[i]];
                int targetNodeId = (targetVarIndices[i] != -1) ? bindings[targetVarIndices[i]] : literalTargets[i];
                String s = entityDict.getString(sourceNodeId);
                String p = relationDict.getString(relations[i]);
                String o = entityDict.getString(targetNodeId);
                if (dirs[i] == Direction.FORWARD) {
                    triples.add(new Triple(s, p, o));
                } else {
                    triples.add(new Triple(o, p, s));
                }
            }
            return checkSuccess(graph, startNodeId, startVarName, headVariable, bindings, varNames, results, triples, targetRelationId, predictObject);
        }

        int relId = relations[stepIndex];
        if (relId == -1) {
            return true;
        }

        int sourceNodeId = bindings[sourceVarIndices[stepIndex]];
        int[] targets = (dirs[stepIndex] == Direction.FORWARD) ?
                graph.getForwardTargets(sourceNodeId, relId) :
                graph.getBackwardTargets(sourceNodeId, relId);

        if (targets == null) {
            return true;
        }

        int targetVarIdx = targetVarIndices[stepIndex];
        int litTargetId = literalTargets[stepIndex];

        for (int nextNode : targets) {
            if (branchCounter[0] <= 0) {
                return true;
            }
            branchCounter[0]--;

            if (targetVarIdx != -1) {
                if (bindings[targetVarIdx] != -1) {
                    if (bindings[targetVarIdx] != nextNode) {
                        continue;
                    }
                    boolean continueSearch = doPatternSearch(startNodeId, stepIndex + 1, relations, dirs, sourceVarIndices, targetVarIndices, literalTargets, varNames, bindings, results, targetRelationId, startVarName, headVariable,predictObject, branchCounter);
                    if (!continueSearch) return false;
                } else {
                    if (containsValueForPattern(bindings, nextNode)) {
                        continue;
                    }
                    bindings[targetVarIdx] = nextNode;
                    boolean continueSearch = doPatternSearch(startNodeId, stepIndex + 1, relations, dirs, sourceVarIndices, targetVarIndices, literalTargets, varNames, bindings, results, targetRelationId, startVarName, headVariable,predictObject, branchCounter);
                    bindings[targetVarIdx] = -1;
                    if (!continueSearch) return false;
                }
            } else {
                if (litTargetId != -1 && nextNode != litTargetId) {
                    continue;
                }
                boolean continueSearch = doPatternSearch(startNodeId, stepIndex + 1, relations, dirs, sourceVarIndices, targetVarIndices, literalTargets, varNames, bindings, results, targetRelationId, startVarName, headVariable,predictObject, branchCounter);
                if (!continueSearch) return false;
            }
        }
        return true;
    }

    protected boolean containsValueForPattern(int[] bindings, int value) {
        for (int binding : bindings) {
            if (binding == value) {
                return true;
            }
        }
        return false;
    }

    protected boolean isQueryValid(String queryNodeStr, String targetRelationStr, Boolean predictObject) {
        return true;
    }

    protected boolean checkStartingNodeSuccess(Graph graph, int uniqueEntitiesCount, int knownEntityId, int headRelationId, int startNodeId, Boolean predictObject) {
        return true;
    }

    protected boolean checkSuccess(Graph graph, int startNodeId, String startVarName, String headVariable, int[] bindings, String[] varNames, List<GroundingResult> ruleGroundings, List<Triple> triples, int targetRelationId, Boolean predictObject) {

        Map<String, String> successfulBinding = new HashMap<>();

        if (startVarName != null && startVarName.length() == 1) {
            successfulBinding.put(startVarName, entityDict.getString(startNodeId));
        }

        boolean headVarInBindings = false;
        int headVarIdx = -1;
        for (int i = 0; i < varNames.length; i++) {
            if (nameEquals(varNames[i], headVariable)) {
                headVarInBindings = true;
                headVarIdx = i;
                break;
            }
        }
        if (!headVarInBindings && headVariable != null && headVariable.length() == 1 && this.entityDict.lookup(headVariable) == -1) {
             successfulBinding.put(headVariable, entityDict.getString(startNodeId));
        }

        for (int i = 0; i < bindings.length; i++) {
            if (varNames[i] != null && bindings[i] != -1) {
                successfulBinding.put(varNames[i], entityDict.getString(bindings[i]));
            }
        }

        // --- Extension Logic ---
        int predictedNodeId = (headVarIdx != -1) ? bindings[headVarIdx] : startNodeId;

        // 1. Add the predicted triple
        String s = entityDict.getString(predictObject ? startNodeId : predictedNodeId);
        String p = relationDict.getString(targetRelationId);
        String o = entityDict.getString(predictObject ? predictedNodeId : startNodeId);
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

        ruleGroundings.add(new GroundingResult(successfulBinding, triples, neighborhoodEdgeCount, nodeIds));

        return true;
    }

    private boolean nameEquals(String varName, String target) {
        return varName != null && varName.equals(target);
    }

    /** Counts neighborhood edges and collects unique neighbor entity IDs without string allocation. */
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
    
    public int entityCount() {
        return entityDict.size();
    }

    protected boolean containsValue(int[] bindings, int value, int currentIndex) {
        for (int i = 0; i < currentIndex; i++) {
            if (bindings[i] == value) {
                return true;
            }
        }
        return false;
    }

    public int entityToId(String entity) {
        return entityDict.lookup(entity);
    }

    public String idToEntity(int id) {
        return entityDict.getString(id);
    }
}

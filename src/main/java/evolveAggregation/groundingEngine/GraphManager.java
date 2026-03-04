package evolveAggregation.groundingEngine;

import evolveAggregation.domain.RuleStep;
import evolveAggregation.domain.Direction;
import evolveAggregation.optimizedGraph.Graph;
import evolveAggregation.optimizedGraph.GraphDictionary;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GraphManager {

    // The Manager owns the Dictionaries and the raw Graph structure
    private final GraphDictionary entityDict;
    private final GraphDictionary relationDict;
    private final Graph graph;

    public GraphManager() {
        this.entityDict = new GraphDictionary();
        this.relationDict = new GraphDictionary();
        this.graph = new Graph();
    }

    // --- 1. Graph Building (File I/O & Translation) ---

    public void parseTriples(String filePath, String separator) {
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty() || line.startsWith("#")) continue;

                String[] parts = line.split(separator);
                if (parts.length >= 3) {
                    // Translate Strings to Ints
                    int subId = entityDict.getId(parts[0].trim());
                    int relId = relationDict.getId(parts[1].trim());
                    int objId = entityDict.getId(parts[2].trim());

                    // Push ints to the Graph
                    graph.addTriple(subId, relId, objId);
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to read file: " + e.getMessage());
        }
    }

    /**
     * MUST be called after all files are parsed to optimize the graph for search.
     */
    public void finalizeGraph() {
        graph.freezeGraph();
    }


//    // --- 2. Grounding Search (The "Hot Loop") ---
//
//    /**
//     * Entry point for applying rules. We map Strings to IDs right at the start
//     * so the deep recursive search only uses primitive integers.
//     */
//    public void startGroundingSearch(String startNodeStr, List<RuleStep> stringSteps, List<Map<String, String>> finalResults) {
//        int startNodeId = entityDict.lookup(startNodeStr);
//        if (startNodeId == -1) return; // Node doesn't exist in graph
//
//        // Translate the rule steps into integer equivalents for speed
//        int[] stepRelations = new int[stringSteps.size()];
//        int[] stepLiteralTargets = new int[stringSteps.size()];
//        Direction[] stepDirections = new Direction[stringSteps.size()];
//        String[] stepVarNames = new String[stringSteps.size()]; // Keep strings just for the final results map
//
//        for (int i = 0; i < stringSteps.size(); i++) {
//            RuleStep step = stringSteps.get(i);
//            stepRelations[i] = relationDict.lookup(step.predicate);
//            stepDirections[i] = step.direction;
//            stepVarNames[i] = step.targetVarName;
//
//            if (step.targetLiteral != null) {
//                stepLiteralTargets[i] = entityDict.lookup(step.targetLiteral);
//            } else {
//                stepLiteralTargets[i] = -1; // -1 means it's a variable, not a literal
//            }
//        }
//
//        // Variable bindings. Array size matches rule length. Indexed by step depth.
//        // Initialize with -1 (unbound)
//        int[] currentBindings = new int[stringSteps.size()];
//        for(int i=0; i<currentBindings.length; i++) currentBindings[i] = -1;
//
//        // Kick off the primitive recursive search
//        searchGrounding(startNodeId, stepRelations, stepDirections, stepLiteralTargets, stepVarNames, 0, currentBindings, finalResults);
//    }


//    private void searchGrounding(int currentNode, int[] relations, Direction[] dirs, int[] literalTargets, String[] varNames,
//                          int stepIndex, int[] bindings, List<Map<String, String>> finalResults) {
//
//        // Base Case: Grounding successful!
//        if (stepIndex == relations.length) {
//            // Translate the bound integer IDs back to Strings for the final results
//            Map<String, String> successfulBinding = new HashMap<>();
//            for (int i = 0; i < bindings.length; i++) {
//                if (varNames[i] != null && bindings[i] != -1) {
//                    successfulBinding.put(varNames[i], entityDict.getString(bindings[i]));
//                }
//            }
//            finalResults.add(successfulBinding);
//            return;
//        }
//
//        int relId = relations[stepIndex];
//        if (relId == -1) return; // The rule uses a relation that isn't in our graph
//
//        // O(log R) fetch of specific targets
//        int[] targets = (dirs[stepIndex] == Direction.FORWARD) ?
//                graph.getForwardTargets(currentNode, relId) :
//                graph.getBackwardTargets(currentNode, relId);
//
//        if (targets == null) return; // Dead end
//
//        // Try each potential path
//        for (int nextNode : targets) {
//
//            // Scenario 1: Target is a literal (e.g., "London")
//            if (literalTargets[stepIndex] != -1) {
//                if (nextNode != literalTargets[stepIndex]) continue;
//            }
//            // Scenario 2: Target is a Variable (e.g., "X")
//            else {
//                // To keep it simple, we just use the stepIndex as the variable's ID
//                // UNIQUENESS CHECK: Ensure nextNode isn't already bound to a previous variable in this path
//                if (containsValue(bindings, nextNode, stepIndex)) continue;
//
//                bindings[stepIndex] = nextNode; // Bind it
//            }
//
//            // Recurse down
//            doSearch(nextNode, relations, dirs, literalTargets, varNames, stepIndex + 1, bindings, finalResults);
//
//            // Backtrack
//            if (literalTargets[stepIndex] == -1) {
//                bindings[stepIndex] = -1;
//            }
//        }
//    }

    // High speed linear scan replacing HashMap.containsValue()
    private boolean containsValue(int[] bindings, int value, int currentIndex) {
        for (int i = 0; i < currentIndex; i++) {
            if (bindings[i] == value) return true;
        }
        return false;
    }

    // Useful getters
    public GraphDictionary getEntityDict() { return entityDict; }
    public GraphDictionary getRelationDict() { return relationDict; }
    public Graph getGraph() { return graph; }
}
package evolveAggregation.groundingEngine;
import evolveAggregation.domain.Direction;
import evolveAggregation.domain.KG.KGVertex;
import evolveAggregation.domain.KG.Triple;
import evolveAggregation.domain.KG.KGEdge;
import evolveAggregation.domain.rules.RuleStep;
import evolveAggregation.domain.rules.groundingTuple;
import org.jgrapht.Graph;
import org.jgrapht.graph.*;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;

public class GraphManager {
    public Graph<KGVertex, KGEdge> getGraph() {
        return graph;
    }

    private Graph<KGVertex, KGEdge> graph =
            new DirectedPseudograph<>(KGEdge.class);

    public void addTriple(Triple t) {
        KGVertex s= new KGVertex(t.subject());
        KGVertex o= new KGVertex(t.object());
        graph.addVertex(s);
        graph.addVertex(o);

        graph.addEdge(s, o, new KGEdge(t.predicate()));
    }

//    public List<String> query(String subject, String predicate) {
//        // This is where your recursive pathfinding / rule application logic will go
//        return new ArrayList<>();
//    }

    public void parseTriples(String filePath, String separator) {
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // skip comments or empty lines
                if (line.trim().isEmpty() || line.startsWith("#")) continue;

                String[] parts = line.split(separator);
                if (parts.length == 3) {
                    addTriple(new Triple(parts[0], parts[1], parts[2]));
                }
            }
        } catch (IOException e) {
            System.err.println("Error reading the file: " + e.getMessage());
        }
    }

    public void printGraph() {
        System.out.println(graph);
    }

    public KGVertex findNode(String name) {
        return graph.vertexSet().stream()
                .filter(v -> v.getUri().equals(name))
                .findAny()
                .orElse(null);
    }

    public void searchGrounding(KGVertex currentNode,
                                 List<RuleStep> ruleSteps,
                                 int stepIndex,
                                 List<KGEdge> currentPath,
                                 Map<String, String> currentBindings,
                                 List<groundingTuple> results) {

        // BASE CASE: All steps satisfied? Save result and return.
        if (stepIndex == ruleSteps.size()) {
            //results.add(new ArrayList<>(currentPath));
            results.add(new groundingTuple(new ArrayList<>(currentPath), new HashMap<>(currentBindings) ));
            return;
        }

        RuleStep currentStep = ruleSteps.get(stepIndex);

        // 1. Determine Candidates based on Direction
        Set<KGEdge> candidates;
        if (currentStep.direction == Direction.FORWARD) {
            candidates = graph.outgoingEdgesOf(currentNode);
        } else {
            // Handles the "p1 <- p2" scenario (incoming edges)
            candidates = graph.incomingEdgesOf(currentNode);
        }

        for (KGEdge edge : candidates) {

            // 2. Filter by Edge Type (Label)
            if (!edge.getPredicate().equals(currentStep.predicate)) {
                continue;
            }

            // Calculate the candidate node we are landing on
            KGVertex nextNode = (currentStep.direction == Direction.FORWARD)
                    ? graph.getEdgeTarget(edge)
                    : graph.getEdgeSource(edge);

            // 3. CHECK CONSTRAINTS & BINDINGS
            boolean newBindingCreated = false;
            String varKey = currentStep.targetVarName;

            // --- Case A: Literal Constraint (e.g., target must be "London") ---
            if (currentStep.targetLiteral != null) {
                if (!nextNode.equals(currentStep.targetLiteral)) {
                    continue; // Mismatch with literal, skip
                }
            }

            // --- Case B: Variable Constraint (e.g., target is "Y") ---
            else if (varKey != null) {
                if (currentBindings.containsKey(varKey)) {
                    // Scenario: Variable "Y" was already bound in a previous step.
                    // Check: Does the current node match the existing binding?
                    if (!currentBindings.get(varKey).equals(nextNode)) {
                        continue; // Inconsistency (Y cannot be Paris AND Berlin), skip
                    }
                } else {
                    // Scenario: Variable "Y" is new. We need to bind it.

                    // *** CRITICAL CHECK: UNIQUENESS ***
                    // Check if this node is ALREADY bound to a DIFFERENT variable.
                    // i.e., If X="Paris", we cannot bind Y="Paris".
                    if (currentBindings.containsValue(nextNode)) {
                        continue; // Node collision! "Paris" is already taken by another variable. Skip.
                    }

                    // Bind the variable
                    currentBindings.put(varKey, nextNode.getUri());
                    newBindingCreated = true;
                }
            }

            // 4. RECURSE (DFS)
            currentPath.add(edge);
            searchGrounding(nextNode, ruleSteps, stepIndex + 1, currentPath, currentBindings, results);

            // 5. BACKTRACK (Clean up for next iteration)
            currentPath.remove(currentPath.size() - 1);
            if (newBindingCreated) {
                currentBindings.remove(varKey); // Unbind "Y" so other paths can try different nodes for it
            }
        }
    }
}

package evolveAggregation.groundingEngine;

import evolveAggregation.domain.*;
import evolveAggregation.domain.KG.KGEdge;
import evolveAggregation.domain.KG.KGVertex;
import evolveAggregation.domain.rules.GroundedRulePath;
import evolveAggregation.domain.rules.Rule;
import evolveAggregation.domain.rules.RuleStep;
import org.jgrapht.Graph;
import java.util.*;

/**
 * A Pattern Matcher for DirectedPseudographs that enforces strict variable uniqueness.
 * If a path is defined as X -> Y, X cannot be equal to Y.
 */
public class VariablePatternMatcher {

    public record groundingTuple(List<KGEdge> edgeList,Map<String, String> bindings){}

    public void applyRule(GraphManager gm, String startNodeString, Rule rule, Map<String, List<GroundedRulePath>> predictions, Direction direction) {
        //List<List<KGEdge>> results = new ArrayList<>();
        List<groundingTuple> results = new ArrayList<>();
//        Map<String, String> currentBindings = new HashMap<>();
        if (direction == Direction.FORWARD) {
            searchGrounding(gm.getGraph(), gm.findNode(startNodeString), rule.getForwardSteps(), 0, new ArrayList<>(), new HashMap<>(), results);
        } else {
            searchGrounding(gm.getGraph(), gm.findNode(startNodeString), rule.getBackwardSteps(), 0, new ArrayList<>(), new HashMap<>(), results);
        }

        for (groundingTuple gt : results) {
            //String predictedNode = gt.bindings.get(rule.getHead().getObject()); //todo: differentiate based on rule type
            String predictedNode = rule.matchBinding(gt.bindings, direction);
            List<KGEdge> edgeList = gt.edgeList();
            predictions.computeIfAbsent(predictedNode, k -> new ArrayList<>()).add(new GroundedRulePath(rule.getConfidence(), edgeList));
        }
        System.out.println(results);
//        return results;
    }

    private void searchGrounding(Graph<KGVertex, KGEdge> graph,
                                 KGVertex currentNode,
                                 List<RuleStep> ruleSteps,
                                 int stepIndex,
                                 List<KGEdge> currentPath,
                                 Map<String, String> currentBindings,
                                 //List<List<KGEdge>> results,
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
            searchGrounding(graph, nextNode, ruleSteps, stepIndex + 1, currentPath, currentBindings, results);

            // 5. BACKTRACK (Clean up for next iteration)
            currentPath.remove(currentPath.size() - 1);
            if (newBindingCreated) {
                currentBindings.remove(varKey); // Unbind "Y" so other paths can try different nodes for it
            }
        }
    }
}
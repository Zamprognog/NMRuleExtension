package evolveAggregation.rules;

import evolveAggregation.groundingEngine.GroundingEngine;

import java.util.*;

public class UnaryRule extends Rule {
    public UnaryRule(RuleAtom headAtom, List<RuleAtom> bodyAtoms, String ruleString, Float confidence) {
        super(headAtom, bodyAtoms, ruleString, confidence);
        if (head.isSubjectVariable()) {
            this.getForwardSteps().addAll(makeStepsFromVariable(body, head.getSubject()));
        } else {
            forwardSteps.addAll(makeStepsFromVariable(body, head.getObject()));
        }
    }


    @Override
    protected List<RulePathStep> makeStepsFromVariable(List<RuleAtom> body, String variable) {
        List<RulePathStep> steps = new ArrayList<>();
        for (RuleAtom a : body){

            if (a.getSubject().equals(variable)){
                // the last variable is the subject of this ruleatom
                if ( a.isObjectVariable() ) {
                    steps.add(RulePathStep.variable(Direction.FORWARD, a.getPredicate(), a.getObject()));
                } else {
                    steps.add(RulePathStep.literal(Direction.FORWARD, a.getPredicate(), a.getObject()));
                }
                variable = a.getObject();
            } else {
                // the last variable is the object of this ruleatom
                if (a.isSubjectVariable()) {
                    steps.add(RulePathStep.variable(Direction.BACKWARD, a.getPredicate(), a.getSubject()));
                } else {
                    steps.add(RulePathStep.literal(Direction.BACKWARD, a.getPredicate(), a.getSubject()));
                }
                variable = a.getSubject();
            }


        }
        return steps;
    }


    
@Override
public void apply(GroundingEngine engine, Boolean predictObject, String startNodeString, String headRelation, Map<String, List<Float>> predictions) {

    // Determine if we are trying to predict the exact variable that forms the starting point of this unary rule's path.
    // For example: head is p(c, X) and query is (s, p, ?) -> predictObject=true, head.isSubjectVariable()=false -> predictHeadVariable=true
    boolean predictHeadVariable = (predictObject && !head.isSubjectVariable()) || (!predictObject && head.isSubjectVariable());

    if (predictHeadVariable) {
        // Query asks for the rule's variable.
        // First, verify that the known entity from the query matches the literal in the rule head.
        if (startNodeString != null) {
            String requiredLiteral = predictObject ? head.getSubject() : head.getObject();
            if (!startNodeString.equals(requiredLiteral)) {
                return; // The rule's head literal doesn't match the query's known entity; skip this rule.
            }
        }

        // Loop over all nodes in the graph to find candidate starting nodes that satisfy the rule's body path.
        // (Assumes GroundingEngine.findSatisfyingStartNodes() is implemented as previously discussed)

        List<String> validStarts = engine.findSatisfyingStartNodes(getForwardSteps(), headRelation, null, predictObject, startNodeString);

        for (String candidateEntityStr : validStarts) {
            // The candidate starting node IS the predicted variable (e.g. 'X')!
            predictions.computeIfAbsent(candidateEntityStr, k -> new ArrayList<>()).add(getConfidence());
        }

    } else {
        // Query asks for the part that is a literal constant in the rule head.
        // For example: head is p(c, X) but query is (?, p, o).
        // This means the query's known entity ('o') maps to the variable ('X'), so we can start our path search directly from it.
        if (startNodeString == null) {
            return; // We need a starting point to run a direct path search.
        }
        String headVariable = predictObject ? head.getObject() : head.getSubject();
        List<Map<String, String>> results = engine.findPathGroundings(startNodeString, getForwardSteps(), headRelation, headVariable, predictObject);

        if (!results.isEmpty()) {
            String predictedNode = predictObject ? head.getObject() : head.getSubject();
            if (predictedNode != null) {
                predictions.computeIfAbsent(predictedNode, k -> new ArrayList<>()).add(getConfidence());
            }
        }
    }
}

}

package evolveAggregation.rules;

import evolveAggregation.groundingEngine.GroundingEngine;

import java.util.*;

public class BinaryRule extends Rule{

    public BinaryRule(RuleAtom headAtom, List<RuleAtom> bodyAtoms, String ruleString, Float confidence) {
        super(headAtom, bodyAtoms, ruleString, confidence);
        forwardSteps.addAll(makeStepsFromVariable(body, head.getSubject()));
        backwardSteps.addAll(makeStepsFromVariable(body.reversed(), head.getObject()));
    }

    @Override
    protected List<RulePathStep> makeStepsFromVariable(List<RuleAtom> body, String variable) {
        List<RulePathStep> steps = new ArrayList<>();

        for (RuleAtom a : body){
            if (a.getSubject().equals(variable)){
                // the last variable is the subject of this ruleatom
                steps.add(RulePathStep.variable(Direction.FORWARD, a.getPredicate(), a.getObject()));
                variable = a.getObject();
            } else {
                // the last variable is the object of this ruleatom
                steps.add(RulePathStep.variable(Direction.BACKWARD, a.getPredicate(), a.getSubject()));
                variable = a.getSubject();
            }
        }
        return steps;
    }

    @Override
    public void apply(GroundingEngine engine, Boolean predictObject, String startNodeString, String headRelation, Map<String, List<Float>> predictions) {

        List<Map<String, String>> results = predictObject ?
                engine.findPathGroundings(startNodeString, getForwardSteps(), headRelation, head.getObject(), true) :
                engine.findPathGroundings(startNodeString, getBackwardSteps(), headRelation, head.getSubject(), false);

        // 1. Deduplicate predictions made by THIS specific rule
        Set<String> uniquePredictions = new HashSet<>();
        for (Map<String, String> bindings : results) {
            String predictedNode = predictObject ? bindings.get(head.getObject()) : bindings.get(head.getSubject());
            if (predictedNode != null) {
                uniquePredictions.add(predictedNode);
            }
        }

        // 2. Add this rule's confidence exactly ONCE per candidate
        for (String predictedNode : uniquePredictions) {
            predictions.computeIfAbsent(predictedNode, k -> new ArrayList<>()).add(getConfidence());
        }
    }

}

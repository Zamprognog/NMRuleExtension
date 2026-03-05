package evolveAggregation.rules;

import evolveAggregation.domain.*;
import evolveAggregation.groundingEngine.GraphManager;
import evolveAggregation.groundingEngine.GroundingEngine;

import java.util.*;

public class BinaryRule extends Rule{

    public BinaryRule(RuleAtom headAtom, List<RuleAtom> bodyAtoms, String ruleString, Float confidence) {
        super(headAtom, bodyAtoms, ruleString, confidence);
        forwardSteps.addAll(makeStepsFromVariable(body, head.getSubject()));
        backwardSteps.addAll(makeStepsFromVariable(body.reversed(), head.getObject()));
    }

    @Override
    protected List<RuleStep> makeStepsFromVariable(List<RuleAtom> body, String variable) {
        List<RuleStep> steps = new ArrayList<>();

        for (RuleAtom a : body){
            if (a.getSubject().equals(variable)){
                // the last variable is the subject of this ruleatom
                steps.add(RuleStep.variable(Direction.FORWARD, a.getPredicate(), a.getObject()));
                variable = a.getObject();
            } else {
                // the last variable is the object of this ruleatom
                steps.add(RuleStep.variable(Direction.BACKWARD, a.getPredicate(), a.getSubject()));
                variable = a.getSubject();
            }
        }
        return steps;
    }

    @Override
    public void apply(GroundingEngine engine, Boolean predictObject, String startNodeString, String targetRelation, Map<String, TreeSet<Float>> predictions) {

        // Let the engine do the heavy lifting
        List<Map<String, String>> results;
        if (predictObject) {

            results = engine.findBindings(startNodeString, getForwardSteps(),targetRelation, true);
        } else {
            results = engine.findBindings(startNodeString, getBackwardSteps(),targetRelation, false);
        }

        // Process predictions exactly as you did before
        for (Map<String, String> bindings : results) {
            String predictedNode = predictObject ? bindings.get(head.getObject()) : bindings.get(head.getSubject());

            // Note: ensure predictNode isn't null before adding
            if (predictedNode != null) {
                predictions.computeIfAbsent(predictedNode, k -> new TreeSet<>()).add(getConfidence());
            }
        }
    }

}

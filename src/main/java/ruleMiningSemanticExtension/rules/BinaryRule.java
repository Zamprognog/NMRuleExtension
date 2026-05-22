package ruleMiningSemanticExtension.rules;

import ruleMiningSemanticExtension.domain.PredictionCandidate;
import ruleMiningSemanticExtension.groundingEngine.GroundingEngine;

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
    public void apply(GroundingEngine engine, Boolean predictObject, String startNodeString, String headRelation, Map<String, PredictionCandidate> predictions) {

        String startVarName = predictObject ? head.getSubject() : head.getObject();
        String targetVarName = predictObject ? head.getObject() : head.getSubject();

        List<ruleMiningSemanticExtension.domain.GroundingResult> results = predictObject ?
                engine.findPathGroundings(startNodeString, getForwardSteps(), headRelation, startVarName, targetVarName, true) :
                engine.findPathGroundings(startNodeString, getBackwardSteps(), headRelation, startVarName, targetVarName, false);

        for (ruleMiningSemanticExtension.domain.GroundingResult result : results) {
            String predictedNode = predictObject ? result.bindings().get(head.getObject()) : result.bindings().get(head.getSubject());
            if (predictedNode != null) {
                predictions.computeIfAbsent(predictedNode, ent -> new PredictionCandidate(ent, headRelation))
                        .addGrounding(this, result.triples(), result.neighborhoodEdgeCount(), result.neighborhoodNodeIds());
            }
        }
    }

}

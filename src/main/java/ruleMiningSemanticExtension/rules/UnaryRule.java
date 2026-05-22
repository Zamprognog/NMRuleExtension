package ruleMiningSemanticExtension.rules;

import ruleMiningSemanticExtension.domain.GroundingResult;
import ruleMiningSemanticExtension.domain.PredictionCandidate;
import ruleMiningSemanticExtension.groundingEngine.GroundingEngine;

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
    public void apply(GroundingEngine engine, Boolean predictObject, String startNodeString, String headRelation, Map<String, PredictionCandidate> predictions) {

        // Determine if we are trying to predict the exact variable that forms the starting point of this unary rule's path.
        boolean predictHeadVariable = (predictObject && !head.isSubjectVariable()) || (!predictObject && head.isSubjectVariable());

        if (predictHeadVariable) {
            // Query asks for the rule's variable.
            if (startNodeString != null) {
                String requiredLiteral = predictObject ? head.getSubject() : head.getObject();
                if (!startNodeString.equals(requiredLiteral)) {
                    return;
                }
            }

            String predictedVarName = predictObject ? head.getObject() : head.getSubject();
            List<GroundingResult> validGroundings = engine.findSatisfyingStartNodes(getForwardSteps(), headRelation, predictedVarName, predictObject, startNodeString);

            for (GroundingResult result : validGroundings) {
                String candidateEntityStr = result.bindings().get(predictedVarName);
                if (candidateEntityStr != null) {
                    predictions.computeIfAbsent(candidateEntityStr, ent -> new PredictionCandidate(ent, headRelation))
                            .addGrounding(this, result.triples(), result.neighborhoodEdgeCount(), result.neighborhoodNodeIds());
                }
            }

        } else {
            // Query asks for the part that is a literal constant in the rule head.
            if (startNodeString == null) {
                return;
            }
            String headVariable = predictObject ? head.getObject() : head.getSubject();
            String startVarName = predictObject ? head.getSubject() : head.getObject(); // This is a literal in the rule head for UnaryRule

            List<GroundingResult> results = engine.findPathGroundings(startNodeString, getForwardSteps(), headRelation, startVarName, headVariable, predictObject);

            if (!results.isEmpty()) {
                String predictedNode = predictObject ? head.getObject() : head.getSubject();
                for (GroundingResult result : results) {
                    predictions.computeIfAbsent(predictedNode, ent -> new PredictionCandidate(ent, headRelation))
                            .addGrounding(this, result.triples(), result.neighborhoodEdgeCount(), result.neighborhoodNodeIds());
                }
            }
        }
    }

}

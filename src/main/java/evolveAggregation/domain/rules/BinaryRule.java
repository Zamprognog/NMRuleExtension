package evolveAggregation.domain.rules;

import evolveAggregation.domain.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    public String matchBinding(Map<String, String> bindings, Direction direction) {
        return "";
    }
}

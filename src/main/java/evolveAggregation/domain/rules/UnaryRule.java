package evolveAggregation.domain.rules;

import evolveAggregation.domain.Direction;

import java.util.List;
import java.util.Map;

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
    protected List<RuleStep> makeStepsFromVariable(List<RuleAtom> body, String variable) {
        return List.of();
    }

    @Override
    public String matchBinding(Map<String, String> bindings, Direction direction) {
        return "";
    }
}

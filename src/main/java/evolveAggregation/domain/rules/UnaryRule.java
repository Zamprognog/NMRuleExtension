package evolveAggregation.domain.rules;

import evolveAggregation.domain.Direction;
import evolveAggregation.domain.KG.KGEdge;
import evolveAggregation.groundingEngine.GraphManager;

import java.util.ArrayList;
import java.util.HashMap;
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
        List<RuleStep> steps = new ArrayList<>();
        for (RuleAtom a : body){

            if (a.getSubject().equals(variable)){
                // the last variable is the subject of this ruleatom
                if ( a.isObjectVariable() ) {
                    steps.add(RuleStep.variable(Direction.FORWARD, a.getPredicate(), a.getObject()));
                } else {
                    steps.add(RuleStep.literal(Direction.FORWARD, a.getPredicate(), a.getObject()));
                }
                variable = a.getObject();
            } else {
                // the last variable is the object of this ruleatom
                if (a.isSubjectVariable()) {
                    steps.add(RuleStep.variable(Direction.BACKWARD, a.getPredicate(), a.getSubject()));
                } else {
                    steps.add(RuleStep.literal(Direction.BACKWARD, a.getPredicate(), a.getSubject()));
                }
                variable = a.getSubject();
            }


        }
        return steps;
    }


    @Override
    public void apply(GraphManager gm, Boolean predictObject, String startNodeString, Direction direction, Map<String, List<GroundedRulePath>> predictions) {
        List<groundingTuple> results = new ArrayList<>();


        if (startNodeString == null) {
            // in this case we need to test all possible nodes.
            //#todo must implement
        } else {
            // this case is standard
            if (predictObject) {
                gm.searchGrounding(gm.findNode(startNodeString), getForwardSteps(), 0,
                        new ArrayList<>(), new HashMap<>(Map.of(head.getSubject(),startNodeString)), results);
            } else {
                gm.searchGrounding(gm.findNode(startNodeString), getBackwardSteps(), 0,
                        new ArrayList<>(), new HashMap<>(Map.of(head.getObject(), startNodeString)), results);
            }

            for (groundingTuple gt : results) {
                String predictedNode = predictObject ? head.getObject() : head.getSubject();
                List<KGEdge> edgeList = gt.edgeList();
                predictions.computeIfAbsent(predictedNode, k -> new ArrayList<>()).add(new GroundedRulePath(getConfidence(), edgeList));
            }
        }

    }
//    @Override
//    public String matchBinding(Map<String, String> bindings, Direction direction) {
//        if (head.isSubjectVariable()) {
//            return bindings.getOrDefault(head.getSubject(), "");
//        } else {
//            return bindings.getOrDefault(head.getObject(), "");
//        }
//    }
}

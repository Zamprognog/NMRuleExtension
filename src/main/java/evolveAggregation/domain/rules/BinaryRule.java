package evolveAggregation.domain.rules;

import evolveAggregation.domain.Direction;
import evolveAggregation.domain.KG.KGEdge;
import evolveAggregation.groundingEngine.GraphManager;

import java.util.ArrayList;
import java.util.HashMap;
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
    public void apply(GraphManager gm, Boolean predictObject, String startNodeString, Direction direction, Map<String, List<GroundedRulePath>> predictions ){
        List<groundingTuple> results = new ArrayList<>();

        if (predictObject) {
            gm.searchGrounding(gm.findNode(startNodeString), getForwardSteps(), 0,
                    new ArrayList<>(), new HashMap<>(Map.of(head.getSubject(),startNodeString)), results);
        } else {
            gm.searchGrounding(gm.findNode(startNodeString), getBackwardSteps(), 0,
                    new ArrayList<>(), new HashMap<>(Map.of(head.getObject(), startNodeString)), results);
        }

        for (groundingTuple gt : results) {
            String predictedNode = predictObject ? gt.bindings().get(head.getObject()) : gt.bindings().get(head.getSubject());
            List<KGEdge> edgeList = gt.edgeList();
            predictions.computeIfAbsent(predictedNode, k -> new ArrayList<>()).add(new GroundedRulePath(getConfidence(), edgeList));
        }
    }
//    @Override
//    public String matchBinding(Map<String, String> bindings, Direction direction) {
//        if (direction == Direction.FORWARD) {
//            // predicting object
//            return bindings.getOrDefault(head.getObject(), "");
//        } else {
//            // predicting subject
//            return bindings.getOrDefault(head.getSubject(), "");
//        }
//    }
}

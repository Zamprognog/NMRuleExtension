package evolveAggregation.rules;

import evolveAggregation.domain.*;
import evolveAggregation.groundingEngine.GraphManager;
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


//    @Override
//    public void apply(GraphManager gm, Boolean predictObject, String startNodeString, Direction direction, Map<String, TreeSet<Float>> predictions) {
//        List<Map<String,String>> results = new ArrayList<>();
//
//
//        if (startNodeString == null) {
//            for (KGVertex v : gm.getGraph().vertexSet()) {
//                gm.searchGrounding(v, getForwardSteps(), 0, new ArrayList<>(), new HashMap<>(), results, predictions.size(), 100 );
//            }
//        } else {
//            if (predictObject) {
//                gm.searchGrounding(gm.findNode(startNodeString), getForwardSteps(), 0,
//                        new ArrayList<>(), new HashMap<>(Map.of(head.getSubject(), startNodeString)), results, predictions.size(), 100);
//            } else {
//                gm.searchGrounding(gm.findNode(startNodeString), getBackwardSteps(), 0,
//                        new ArrayList<>(), new HashMap<>(Map.of(head.getObject(), startNodeString)), results, predictions.size(), 100);
//            }
//
//            if (!results.isEmpty()) {
//                String predictedNode = predictObject ? head.getObject() : head.getSubject();
//                predictions.computeIfAbsent(predictedNode, k -> new TreeSet<>()).add(getConfidence());
//            }
//        }
//    }
    @Override
    public void apply(GroundingEngine engine, Boolean predictObject, String startNodeString, Map<String, TreeSet<Float>> predictions) {

        // Let the engine do the heavy lifting
        List<Map<String, String>> results = new ArrayList<>();

        if (startNodeString == null) {
            for (int nodeIdx =0; nodeIdx< engine.entityCount(); nodeIdx ++) {
                String candidate = engine.idToEntity(nodeIdx);
                List<Map<String, String>> tempResults = engine.findBindings(candidate, getForwardSteps());
                for (Map<String, String> binding : tempResults) {
                    if (head.isSubjectVariable()) {
                        binding.put(head.getSubject(), candidate);
                    } else {
                        binding.put(head.getObject(), candidate);
                    }
                    results.add(binding);
                }

            }
        } else {
            //unary rules always predict 'forward'
//            if (predictObject) {
//                results = engine.findBindings(startNodeString, getForwardSteps());
//            } else {
//                results = engine.findBindings(startNodeString, getBackwardSteps());
//            }
            results = engine.findBindings(startNodeString, getForwardSteps());
        }

        // Process predictions exactly as you did before
        for (Map<String, String> bindings : results) {
            // bindings are not needed in unary rules
            String predictedNode = predictObject ? head.getObject() : head.getSubject();
            if (startNodeString == null) {
                predictedNode = bindings.get(head.getObject());
            }
            // Note: ensure predictNode isn't null before adding
            if (predictedNode != null) {
                //this is the 'reverse' case, where we are looking gfor nodes that can ground the head variable
                predictions.computeIfAbsent(predictedNode, k -> new TreeSet<>()).add(getConfidence());
            }
        }
}

}

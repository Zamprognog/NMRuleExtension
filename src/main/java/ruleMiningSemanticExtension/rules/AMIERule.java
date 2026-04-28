package ruleMiningSemanticExtension.rules;

import ruleMiningSemanticExtension.groundingEngine.GroundingEngine;

import java.util.*;

public class AMIERule extends Rule {

    public AMIERule(RuleAtom headAtom, List<RuleAtom> bodyAtoms, String ruleString, Float confidence) {
        super(headAtom, bodyAtoms, ruleString, confidence);
    }

    // Unused for AMIE, we dynamically build steps based on the known starting variable
    @Override
    protected List<RulePathStep> makeStepsFromVariable(List<RuleAtom> body, String variable) {
        return new ArrayList<>();
    }

    /**
     * Orders the body atoms so that every step connects to a previously bound variable.
     */
    private List<RulePatternStep> buildOrderedPattern(String startVarName) {
        List<RulePatternStep> steps = new ArrayList<>();
        Set<String> boundVars = new HashSet<>();
        boundVars.add(startVarName);

        List<RuleAtom> remainingAtoms = new ArrayList<>(body);

        while (!remainingAtoms.isEmpty()) {
            boolean progress = false;
            for (Iterator<RuleAtom> it = remainingAtoms.iterator(); it.hasNext(); ) {
                RuleAtom atom = it.next();

                // If we know the subject, traverse FORWARD
                if (boundVars.contains(atom.getSubject())) {
                    String targetVar = atom.isObjectVariable() ? atom.getObject() : null;
                    String targetLit = atom.isObjectVariable() ? null : atom.getObject();

                    steps.add(new RulePatternStep(Direction.FORWARD, atom.getPredicate(), atom.getSubject(), targetVar, targetLit));
                    if (targetVar != null) boundVars.add(targetVar);
                    it.remove();
                    progress = true;
                    break;
                }
                // If we know the object, traverse BACKWARD
                else if (boundVars.contains(atom.getObject())) {
                    String targetVar = atom.isSubjectVariable() ? atom.getSubject() : null;
                    String targetLit = atom.isSubjectVariable() ? null : atom.getSubject();

                    steps.add(new RulePatternStep(Direction.BACKWARD, atom.getPredicate(), atom.getObject(), targetVar, targetLit));
                    if (targetVar != null) boundVars.add(targetVar);
                    it.remove();
                    progress = true;
                    break;
                }
            }
            if (!progress) {
                // The rule has disconnected components, which shouldn't happen in valid AMIE rules.
                break;
            }
        }
        return steps;
    }

    @Override
    public void apply(GroundingEngine engine, Boolean predictObject, String startNodeString, String targetRelation, Map<String, List<Float>> predictions) {
        // Determine what variable we are starting from and what we want to predict
        String startVar = predictObject ? head.getSubject() : head.getObject();
        String targetVar = predictObject ? head.getObject() : head.getSubject();

        List<RulePatternStep> orderedSteps = buildOrderedPattern(startVar);

        // Call the new pattern matching engine
        List<Map<String, String>> results = engine.findPatternGroundings(startNodeString, orderedSteps, head.getPredicate(), startVar, targetVar, predictObject);

//        for (Map<String, String> grounding : results) {
//            String predictedEntity = grounding.get(targetVar);
//            if (predictedEntity != null) {
//                predictions.computeIfAbsent(predictedEntity, k -> new ArrayList<>()).add(getConfidence());
//            }
//        }
        // 1. Deduplicate predictions made by THIS specific AMIE rule
        Set<String> uniquePredictions = new HashSet<>();
        for (Map<String, String> grounding : results) {
            String predictedEntity = grounding.get(targetVar);
            if (predictedEntity != null) {
                uniquePredictions.add(predictedEntity);
            }
        }

        // 2. Add this rule's confidence exactly ONCE per candidate
        for (String predictedEntity : uniquePredictions) {
            predictions.computeIfAbsent(predictedEntity, k -> new ArrayList<>()).add(getConfidence());
        }
    }
}
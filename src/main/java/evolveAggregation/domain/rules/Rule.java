package evolveAggregation.domain.rules;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import evolveAggregation.domain.Direction;


// Represents one complete "Rule" (e.g., "FindValidShipmentRoute")
public abstract class Rule {
    private static final Pattern ATOM_PATTERN = Pattern.compile("^(.+?)\\((.+?),(.+?)\\)$");

    private final String name;

    public RuleAtom getHead() {
        return head;
    }

    public List<RuleAtom> getBody() {
        return body;
    }

//    public Ruletype getRuletype() {
//        return ruletype;
//    }

    public float getConfidence() {
        return confidence;
    }

    public List<RuleStep> getForwardSteps() {
        return forwardSteps;
    }

    public List<RuleStep> getBackwardSteps() {
        return backwardSteps;
    }

    protected final RuleAtom head;
    protected final List<RuleAtom> body;
    protected final float confidence;
    protected final List<RuleStep> forwardSteps = new ArrayList<>();
    protected final List<RuleStep> backwardSteps = new ArrayList<>();

    Rule(RuleAtom head, List<RuleAtom> body, String originalString, float confidence) {
        this.head = head;
        this.body = body;
        this.name = originalString;
        this.confidence = confidence;
//
//        if (ruletype == Ruletype.binary) {
//
//            forwardSteps.addAll(makeStepsFromVariable(body, head.getSubject()));
//            backwardSteps.addAll(makeStepsFromVariable(body.reversed(), head.getObject()));
//
//        } else {
//            //rule is unary, it's always only one direction
//            if (head.isSubjectVariable()) {
//                forwardSteps.addAll(makeStepsFromVariable(body, head.getSubject()));
//            } else {
//                forwardSteps.addAll(makeStepsFromVariable(body, head.getObject()));
//            }
//        }
//
//
    }


    public static Rule parse(String ruleString, Float confidence) {


        // 1. Split Head <= Body
        String[] parts = ruleString.split("<=");
        if (parts.length < 2) return null;

        // 2. Parse Head
        RuleAtom headAtom = parseAtom(parts[0].trim());
        if (headAtom == null) return null; // Fail fast on bad data

        // 3. Parse Body
        String[] atomStrings = parts[1].trim().split(",\\s+");
        List<RuleAtom> bodyAtoms = new ArrayList<>(atomStrings.length);

        for (String s : atomStrings) {
            RuleAtom a = parseAtom(s);
            if (a != null) bodyAtoms.add(a);
        }

        if (headAtom.isUnary()) {
            return new UnaryRule(headAtom, bodyAtoms, ruleString, confidence);
        } else {
            return new BinaryRule(headAtom, bodyAtoms, ruleString, confidence);
        }
    }

    private static RuleAtom parseAtom(String atomStr) {
        Matcher m = ATOM_PATTERN.matcher(atomStr);
        if (m.find()) {
            return new RuleAtom(m.group(1), m.group(2), m.group(3));
        }
        return null;
    }

    protected abstract List<RuleStep> makeStepsFromVariable(List<RuleAtom> body, String variable);
//    protected List<RuleStep> makeStepsFromVariable(List<RuleAtom> body, String variable){
//        List<RuleStep> steps = new ArrayList<>();
//        for (RuleAtom a : body){
//
//            if (a.getSubject().equals(variable)){
//                // the last variable is the subject of this ruleatom
//                if ( a.isObjectVariable() ) {
//                    steps.add(RuleStep.variable(Direction.FORWARD, a.getPredicate(), a.getObject()));
//                } else {
//                    steps.add(RuleStep.literal(Direction.FORWARD, a.getPredicate(), a.getObject()));
//                }
//                variable = a.getObject();
//            } else {
//                // the last variable is the object of this ruleatom
//                if (a.isSubjectVariable()) {
//                    steps.add(RuleStep.variable(Direction.BACKWARD, a.getPredicate(), a.getSubject()));
//                } else {
//                    steps.add(RuleStep.literal(Direction.BACKWARD, a.getPredicate(), a.getSubject()));
//                }
//                variable = a.getSubject();
//            }
//
//
//        }
//        return steps;
//    }


    public String getName() {
        return this.name;
    }

    public abstract String  matchBinding(Map<String, String> bindings, Direction direction);
}
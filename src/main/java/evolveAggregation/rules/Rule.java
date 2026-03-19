package evolveAggregation.rules;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

//import evolveAggregation.rules.Direction;
//import evolveAggregation.domain.GroundedRulePath;
//import evolveAggregation.groundingEngine.GraphManager;
import evolveAggregation.groundingEngine.GroundingEngine;


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

    public List<RulePathStep> getForwardSteps() {
        return forwardSteps;
    }

    public List<RulePathStep> getBackwardSteps() {
        return backwardSteps;
    }

    protected final RuleAtom head;
    protected final List<RuleAtom> body;
    protected final float confidence;
    protected final List<RulePathStep> forwardSteps = new ArrayList<>();
    protected final List<RulePathStep> backwardSteps = new ArrayList<>();

    Rule(RuleAtom head, List<RuleAtom> body, String originalString, float confidence) {
        this.head = head;
        this.body = body;
        this.name = originalString;
        this.confidence = confidence;
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

    public static Rule parseAmie(String ruleString, Float confidence) {
        String[] parts = ruleString.split("<=");
        if (parts.length < 2) return null;

        RuleAtom headAtom = parseAtom(parts[0].trim());
        if (headAtom == null) return null;

        String[] atomStrings = parts[1].trim().split(",\\s+");
        List<RuleAtom> bodyAtoms = new ArrayList<>(atomStrings.length);

        for (String s : atomStrings) {
            RuleAtom a = parseAtom(s);
            if (a != null) bodyAtoms.add(a);
        }

        return new AMIERule(headAtom, bodyAtoms, ruleString, confidence);
    }

    private static RuleAtom parseAtom(String atomStr) {
        Matcher m = ATOM_PATTERN.matcher(atomStr);
        if (m.find()) {
            return new RuleAtom(m.group(1), m.group(2), m.group(3));
        }
        return null;
    }

    public abstract void apply(GroundingEngine engine, Boolean predictObject, String startNodeString,String targetRelation, Map<String, TreeSet<Float>> predictions);

    protected abstract List<RulePathStep> makeStepsFromVariable(List<RuleAtom> body, String variable);

//    public abstract String  matchBinding(Map<String, String> bindings, Direction direction);

    public String getName() {
        return this.name;
    }


}
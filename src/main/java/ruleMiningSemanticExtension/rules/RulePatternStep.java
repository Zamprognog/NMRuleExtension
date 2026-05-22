package ruleMiningSemanticExtension.rules;

public class RulePatternStep {
    public Direction direction;
    public String predicate;
    public String sourceVarName; // The variable we are expanding FROM
    public String targetVarName; // The variable we are expanding TO
    public String targetLiteral; // The literal node ID we must hit

    public RulePatternStep(Direction direction, String predicate, String sourceVarName, String targetVarName, String targetLiteral) {
        this.direction = direction;
        this.predicate = predicate;
        this.sourceVarName = sourceVarName;
        this.targetVarName = targetVarName;
        this.targetLiteral = targetLiteral;
    }
}
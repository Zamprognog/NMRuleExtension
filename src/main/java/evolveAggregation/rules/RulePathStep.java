package evolveAggregation.rules;

public class RulePathStep {
    public Direction direction;
    public String predicate;        // e.g., "p1"
    public String targetVarName;   // e.g., "X" - The variable we are looking for/checking
    public String targetLiteral;   // e.g., "London" - A specific node ID we must hit

    // Private constructor
    private RulePathStep(Direction direction, String predicate, String targetVarName, String targetLiteral) {
        this.direction = direction;
        this.predicate = predicate;
        this.targetVarName = targetVarName;
        this.targetLiteral = targetLiteral;
    }

    /**
     * Create a rule that binds to a Variable (e.g., X).
     * Example: p1(..., X)
     */
    public static RulePathStep variable(Direction dir, String predicate, String varName) {
        return new RulePathStep(dir, predicate, varName, null);
    }

    /**
     * Create a rule that must match a specific Literal Node ID.
     * Example: p1(..., "London")
     */
    public static RulePathStep literal(Direction dir, String predicate, String nodeId) {
        return new RulePathStep(dir, predicate, null, nodeId);
    }
}
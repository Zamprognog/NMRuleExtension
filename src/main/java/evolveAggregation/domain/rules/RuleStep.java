package evolveAggregation.domain.rules;

import evolveAggregation.domain.Direction;

public class RuleStep {
    public Direction direction;
    public String predicate;        // e.g., "p1"
    public String targetVarName;   // e.g., "X" - The variable we are looking for/checking
    public String targetLiteral;   // e.g., "London" - A specific node ID we must hit

    // Private constructor
    private RuleStep(Direction direction, String predicate, String targetVarName, String targetLiteral) {
        this.direction = direction;
        this.predicate = predicate;
        this.targetVarName = targetVarName;
        this.targetLiteral = targetLiteral;
    }

    /**
     * Create a rule that binds to a Variable (e.g., X).
     * Example: p1(..., X)
     */
    public static RuleStep variable(Direction dir, String predicate, String varName) {
        return new RuleStep(dir, predicate, varName, null);
    }

    /**
     * Create a rule that must match a specific Literal Node ID.
     * Example: p1(..., "London")
     */
    public static RuleStep literal(Direction dir, String predicate, String nodeId) {
        return new RuleStep(dir, predicate, null, nodeId);
    }
}
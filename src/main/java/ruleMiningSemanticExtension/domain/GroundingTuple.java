package ruleMiningSemanticExtension.domain;

import ruleMiningSemanticExtension.rules.Rule;
import java.util.Map;

public record GroundingTuple(Rule rule, Map<String, String> bindings) {}

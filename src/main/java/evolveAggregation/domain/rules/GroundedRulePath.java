package evolveAggregation.domain.rules;

import evolveAggregation.domain.KG.KGEdge;

import java.util.List;

public record GroundedRulePath(Float confidence, List<KGEdge> groundedPath) {
}

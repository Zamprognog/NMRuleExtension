package evolveAggregation.domain.rules;

import evolveAggregation.domain.KG.KGEdge;

import java.util.List;
import java.util.Map;

public record groundingTuple (List<KGEdge> edgeList, Map<String, String> bindings){}

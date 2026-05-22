package ruleMiningSemanticExtension.domain;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record GroundingResult(
        Map<String, String> bindings,
        List<Triple> triples,
        int neighborhoodEdgeCount,
        Set<Integer> neighborhoodNodeIds
) {}

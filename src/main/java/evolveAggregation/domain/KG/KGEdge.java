package evolveAggregation.domain.KG;

import org.jgrapht.graph.DefaultEdge;

public class KGEdge extends DefaultEdge {
    private final String predicate;

    public KGEdge(String predicate) {
        this.predicate = predicate;
    }

    public String getPredicate() {
        return predicate;
    }

    @Override
    public String toString() {
        return "(" + predicate + ")";
    }
}
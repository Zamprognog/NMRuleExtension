package evolveAggregation.domain.KG;

import java.util.Objects;

public class KGVertex {
    private final String label;

    public String getUri() {
        return uri;
    }

    private final String uri;

    public KGVertex(String label) {
        this.label = label;
        this.uri = label;
    }
    public KGVertex(String label, String uri) {
        this.label = label;
        this.uri = uri;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        KGVertex kgVertex = (KGVertex) o;
        return Objects.equals(uri, kgVertex.uri);
    }
    public int hashCode() {
        return Objects.hash(uri);
    }
    @Override
    public String toString() { return label; }
//}
}

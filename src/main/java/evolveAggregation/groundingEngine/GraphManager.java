package evolveAggregation.groundingEngine;
import evolveAggregation.domain.KG.KGVertex;
import evolveAggregation.domain.KG.Triple;
import evolveAggregation.domain.KG.KGEdge;
import org.jgrapht.Graph;
import org.jgrapht.graph.*;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

public class GraphManager {
    public Graph<KGVertex, KGEdge> getGraph() {
        return graph;
    }

    private Graph<KGVertex, KGEdge> graph =
            new DirectedPseudograph<>(KGEdge.class);

    public void addTriple(Triple t) {
        KGVertex s= new KGVertex(t.subject());
        KGVertex o= new KGVertex(t.object());
        graph.addVertex(s);
        graph.addVertex(o);

        graph.addEdge(s, o, new KGEdge(t.predicate()));
    }

//    public List<String> query(String subject, String predicate) {
//        // This is where your recursive pathfinding / rule application logic will go
//        return new ArrayList<>();
//    }

    public void parseTriples(String filePath, String separator) {
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // skip comments or empty lines
                if (line.trim().isEmpty() || line.startsWith("#")) continue;

                String[] parts = line.split(separator);
                if (parts.length == 3) {
                    addTriple(new Triple(parts[0], parts[1], parts[2]));
                }
            }
        } catch (IOException e) {
            System.err.println("Error reading the file: " + e.getMessage());
        }
    }

    public void printGraph() {
        System.out.println(graph);
    }

    public KGVertex findNode(String name) {
        return graph.vertexSet().stream()
                .filter(v -> v.getUri().equals(name))
                .findAny()
                .orElse(null);
    }
}

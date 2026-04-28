package ruleMiningSemanticExtension.graphTools;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/**
 * Orchestrates the lifecycle of the knowledge graph, including its creation,
 * population from data sources, and finalization into an optimized structure.
 * This class acts as a central access point for the {@link Graph} and its
 * corresponding {@link GraphDictionary} instances for entities and relations.
 */
public class GraphManager {

    /** The dictionary mapping entity strings to their unique integer IDs. */
    private final GraphDictionary entityDict;
    /** The dictionary mapping relation strings to their unique integer IDs. */
    private final GraphDictionary relationDict;
    /** The underlying graph structure used to store and query triples. */
    private final Graph graph;

    /**
     * Initializes a new GraphManager with fresh dictionaries and a graph.
     */
    public GraphManager() {
        this.entityDict = new GraphDictionary();
        this.relationDict = new GraphDictionary();
        this.graph = new Graph();
    }

    /**
     * Parses knowledge triples from a delimited text file and adds them to the graph.
     * Each line is expected to contain at least three parts: subject, relation, and object.
     * Strings are automatically converted to integer IDs via the internal dictionaries.
     * Lines starting with '#' or that are empty are ignored.
     *
     * @param filePath The path to the file containing triples.
     * @param separator The delimiter used to separate parts of the triple (e.g., "\t", " ").
     */
    public void parseTriples(String filePath, String separator) {
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty() || line.startsWith("#")) continue;

                String[] parts = line.split(separator);
                if (parts.length >= 3) {
                    // Translate Strings to Ints
                    int subId = entityDict.getId(parts[0].trim());
                    int relId = relationDict.getId(parts[1].trim());
                    int objId = entityDict.getId(parts[2].trim());

                    // Push ints to the Graph
                    graph.addTriple(subId, relId, objId);
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to read file: " + e.getMessage());
        }
    }

    /**
     * Finalizes the graph building process.
     * This method MUST be called after all triples have been parsed to convert the
     * dynamic building structures into optimized frozen arrays for fast traversal.
     */
    public void finalizeGraph() {
        graph.freezeGraph();
    }


    /**
     * Helper method to check if a specific value is already present in an array of bindings.
     * Used to prevent cycles or redundant traversals.
     *
     * @param bindings The array of integer IDs.
     * @param value The value to search for.
     * @param currentIndex The index up to which to search in the array.
     * @return {@code true} if the value is found, {@code false} otherwise.
     */
    private boolean containsValue(int[] bindings, int value, int currentIndex) {
        for (int i = 0; i < currentIndex; i++) {
            if (bindings[i] == value) return true;
        }
        return false;
    }

    /**
     * Returns the entity dictionary.
     * @return The {@link GraphDictionary} for entities.
     */
    public GraphDictionary getEntityDict() { return entityDict; }

    /**
     * Returns the relation dictionary.
     * @return The {@link GraphDictionary} for relations.
     */
    public GraphDictionary getRelationDict() { return relationDict; }

    /**
     * Returns the underlying graph.
     * @return The {@link Graph} instance.
     */
    public Graph getGraph() { return graph; }
}
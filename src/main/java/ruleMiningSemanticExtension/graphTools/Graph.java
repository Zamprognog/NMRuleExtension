package ruleMiningSemanticExtension.graphTools;
import java.util.*;

/**
 * Represents a directed graph optimized for high-speed traversal.
 * The graph operates in two phases:
 * 1. Building Phase: Uses dynamic lists and maps to accumulate triples.
 * 2. Frozen Phase: Converts the dynamic structure into primitive arrays for memory efficiency and fast lookup.
 */
public class Graph {
    /** Stores forward edges (subject -> relation -> object) in the frozen phase. */
    private NodeData[] forwardEdges;
    /** Stores backward edges (object -> relation -> subject) in the frozen phase. */
    private NodeData[] backwardEdges;

    /** Temporary list for building forward edges. */
    private List<Map<Integer, List<Integer>>> tempForward;
    /** Temporary list for building backward edges. */
    private List<Map<Integer, List<Integer>>> tempBackward;

    /**
     * Internal data structure to store relation IDs and their corresponding target nodes.
     * Relations are kept sorted to enable binary search for target retrieval.
     */
    private static class NodeData {
        /** Sorted array of relation IDs. */
        int[] relations;
        /** Array of target node IDs, indexed parallel to relations. */
        int[][] targets;

        /**
         * Constructs NodeData with pre-sorted relations and their targets.
         * @param relations Sorted array of relation IDs.
         * @param targets Array of target node arrays.
         */
        public NodeData(int[] relations, int[][] targets) {
            this.relations = relations;
            this.targets = targets;
        }

        /**
         * Retrieves the target nodes for a specific relation.
         * @param relationId The ID of the relation to look up.
         * @return An array of target node IDs, or null if no targets exist for the relation.
         */
        public int[] getTargets(int relationId) {
            int idx = Arrays.binarySearch(relations, relationId);
            return (idx >= 0) ? targets[idx] : null;
        }
    }

    /**
     * Functional interface for consuming edges of a node.
     */
    @FunctionalInterface
    public interface EdgeConsumer {
        void accept(int relationId, int[] targetIds);
    }

    /**
     * Iterates over all edges of a node in the specified direction.
     * @param nodeId The ID of the node.
     * @param forward True for forward edges, false for backward edges.
     * @param consumer The consumer to handle the relation ID and target node IDs.
     */
    public void forEachEdge(int nodeId, boolean forward, EdgeConsumer consumer) {
        NodeData[] edges = forward ? forwardEdges : backwardEdges;
        if (nodeId < 0 || edges == null || nodeId >= edges.length) return;
        NodeData data = edges[nodeId];
        if (data == null) return;
        for (int i = 0; i < data.relations.length; i++) {
            consumer.accept(data.relations[i], data.targets[i]);
        }
    }

    /**
     * Initializes a new Graph instance in the building phase.
     */
    public Graph() {
        tempForward = new ArrayList<>();
        tempBackward = new ArrayList<>();
    }

    /**
     * Checks if the graph is empty in its frozen state.
     * @return true if both forward and backward edges are empty or null.
     */
    public boolean isEmpty() {
        return (forwardEdges == null || forwardEdges.length == 0) && (backwardEdges == null || backwardEdges.length == 0);
    }

    /**
     * Automatically grows the temporary graph structure to accommodate new node IDs.
     * @param maxNodeId The maximum node ID that needs to be accommodated.
     */
    public void ensureNodeCapacity(int maxNodeId) {
        while (tempForward.size() <= maxNodeId) {
            tempForward.add(new HashMap<>());
            tempBackward.add(new HashMap<>());
        }
    }

    /**
     * Adds a triple to the graph. Only available during the building phase.
     * @param subject The ID of the subject node.
     * @param relation The ID of the relation.
     * @param object The ID of the object node.
     */
    public void addTriple(int subject, int relation, int object) {
        ensureNodeCapacity(Math.max(subject, object));
        tempForward.get(subject).computeIfAbsent(relation, k -> new ArrayList<>()).add(object);
        tempBackward.get(object).computeIfAbsent(relation, k -> new ArrayList<>()).add(subject);
    }

    /**
     * Finalizes the graph structure, converting dynamic maps into optimized primitive arrays.
     * Clears temporary building structures to free memory.
     */
    public void freezeGraph() {
        int numNodes = tempForward.size();
        forwardEdges = new NodeData[numNodes];
        backwardEdges = new NodeData[numNodes];

        for (int i = 0; i < numNodes; i++) {
            forwardEdges[i] = freezeNode(tempForward.get(i));
            backwardEdges[i] = freezeNode(tempBackward.get(i));
        }

        // Free memory
        tempForward = null;
        tempBackward = null;
    }

    /**
     * Converts a single node's edge map into an optimized NodeData object.
     * @param edgeMap Map from relation ID to list of target node IDs.
     * @return A NodeData object, or null if the map is empty.
     */
    private NodeData freezeNode(Map<Integer, List<Integer>> edgeMap) {
        if (edgeMap == null || edgeMap.isEmpty()) return null;

        int[] rels = new int[edgeMap.size()];
        int[][] targs = new int[edgeMap.size()][];

        int i = 0;
        for (Map.Entry<Integer, List<Integer>> entry : edgeMap.entrySet()) {
            rels[i] = entry.getKey();
            targs[i] = entry.getValue().stream().mapToInt(Integer::intValue).toArray();
            i++;
        }

        sortParallel(rels, targs);
        return new NodeData(rels, targs);
    }

    /**
     * Retrieves target nodes reachable from the given nodeId via the specified relationId in the forward direction.
     * @param nodeId The ID of the source node.
     * @param relationId The ID of the relation.
     * @return Array of target node IDs, or null if none found.
     */
    public int[] getForwardTargets(int nodeId, int relationId) {
        if (nodeId < 0 || forwardEdges == null || nodeId >= forwardEdges.length) return null;
        NodeData data = forwardEdges[nodeId];
        return data == null ? null : data.getTargets(relationId);
    }

    /**
     * Retrieves source nodes that reach the given nodeId via the specified relationId (backward traversal).
     * @param nodeId The ID of the target node.
     * @param relationId The ID of the relation.
     * @return Array of source node IDs, or null if none found.
     */
    public int[] getBackwardTargets(int nodeId, int relationId) {
        if (nodeId < 0 || backwardEdges == null || nodeId >= backwardEdges.length) return null;
        NodeData data = backwardEdges[nodeId];
        return data == null ? null : data.getTargets(relationId);
    }

    /**
     * Sorts relation IDs and their corresponding target arrays in parallel based on relation IDs.
     * @param keys The array of relation IDs to sort.
     * @param values The array of target arrays to be reordered along with keys.
     */
    private void sortParallel(int[] keys, int[][] values) {
        if (keys == null || keys.length <= 1) return;
        quickSort(keys, values, 0, keys.length - 1);
    }

    /**
     * QuickSort implementation for parallel arrays.
     */
    private void quickSort(int[] keys, int[][] values, int low, int high) {
        if (low < high) {
            int pivotIndex = partition(keys, values, low, high);
            quickSort(keys, values, low, pivotIndex - 1);
            quickSort(keys, values, pivotIndex + 1, high);
        }
    }

    /**
     * Partition step for QuickSort on parallel arrays.
     */
    private int partition(int[] keys, int[][] values, int low, int high) {
        int pivot = keys[high];
        int i = (low - 1);
        for (int j = low; j < high; j++) {
            if (keys[j] <= pivot) {
                i++;
                swap(keys, values, i, j);
            }
        }
        swap(keys, values, i + 1, high);
        return i + 1;
    }

    /**
     * Swaps elements in two parallel arrays.
     */
    private void swap(int[] keys, int[][] values, int i, int j) {
        int tempKey = keys[i]; keys[i] = keys[j]; keys[j] = tempKey;
        int[] tempVal = values[i]; values[i] = values[j]; values[j] = tempVal;
    }
}
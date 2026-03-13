package evolveAggregation.optimizedGraph;
import java.util.*;

public class Graph {
    // Phase 2 Static Arrays (Frozen)
    private NodeData[] forwardEdges;
    private NodeData[] backwardEdges;

    // Phase 1 Dynamic Lists (Building)
    private List<Map<Integer, List<Integer>>> tempForward;
    private List<Map<Integer, List<Integer>>> tempBackward;

    private static class NodeData {
        int[] relations;
        int[][] targets;

        public NodeData(int[] relations, int[][] targets) {
            this.relations = relations;
            this.targets = targets;
        }

        public int[] getTargets(int relationId) {
            int idx = Arrays.binarySearch(relations, relationId);
            return (idx >= 0) ? targets[idx] : null;
        }
    }

    public Graph() {
        tempForward = new ArrayList<>();
        tempBackward = new ArrayList<>();
    }

    public boolean isEmpty() {
        return forwardEdges.length ==0 && backwardEdges.length==0 ;
    }

    // Automatically grows the graph as new nodes are discovered
    public void ensureNodeCapacity(int maxNodeId) {
        while (tempForward.size() <= maxNodeId) {
            tempForward.add(new HashMap<>());
            tempBackward.add(new HashMap<>());
        }
    }

    // Called by GraphManager during file parsing
    public void addTriple(int subject, int relation, int object) {
        ensureNodeCapacity(Math.max(subject, object));
        tempForward.get(subject).computeIfAbsent(relation, k -> new ArrayList<>()).add(object);
        tempBackward.get(object).computeIfAbsent(relation, k -> new ArrayList<>()).add(subject);
    }

    // Converts dynamic Maps into ultra-fast primitive arrays
    public void freezeGraph() {
        int numNodes = tempForward.size();
        forwardEdges = new NodeData[numNodes];
        backwardEdges = new NodeData[numNodes];

        for (int i = 0; i < numNodes; i++) {
            forwardEdges[i] = freezeNode(tempForward.get(i));
            backwardEdges[i] = freezeNode(tempBackward.get(i));
        }

        // Free memory!
        tempForward = null;
        tempBackward = null;
    }

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

    // --- High Speed Accessors ---
    public int[] getForwardTargets(int nodeId, int relationId) {
        if (nodeId < 0 || forwardEdges == null || nodeId >= forwardEdges.length) return null;
        NodeData data = forwardEdges[nodeId];
        return data == null ? null : data.getTargets(relationId);
    }

    public int[] getBackwardTargets(int nodeId, int relationId) {
        if (nodeId < 0 || backwardEdges == null || nodeId >= backwardEdges.length) return null;
        NodeData data = backwardEdges[nodeId];
        return data == null ? null : data.getTargets(relationId);
    }

    // --- Sorting logic ---
    private void sortParallel(int[] keys, int[][] values) {
        if (keys == null || keys.length <= 1) return;
        quickSort(keys, values, 0, keys.length - 1);
    }

    private void quickSort(int[] keys, int[][] values, int low, int high) {
        if (low < high) {
            int pivotIndex = partition(keys, values, low, high);
            quickSort(keys, values, low, pivotIndex - 1);
            quickSort(keys, values, pivotIndex + 1, high);
        }
    }

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

    private void swap(int[] keys, int[][] values, int i, int j) {
        int tempKey = keys[i]; keys[i] = keys[j]; keys[j] = tempKey;
        int[] tempVal = values[i]; values[i] = values[j]; values[j] = tempVal;
    }
}
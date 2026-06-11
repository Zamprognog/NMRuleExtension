package ruleMiningSemanticExtension.groundingEngine;

import ruleMiningSemanticExtension.domain.PredictionCandidate;

import java.util.*;

public class RankingTree {

    // --- Inner Class: The Candidate ---
    public static class Candidate {
        public String entity;
        /** Confidences sorted descending, owned by this object (copy of cache). */
        public float[] confidences;

        public Candidate(String entity, float[] confidences) {
            this.entity = entity;
            // defensive copy so callers that hold a reference can't corrupt the sort
            this.confidences = confidences.clone();
            sortDescending(this.confidences);
        }

        public Candidate(PredictionCandidate pc) {
            this.entity = pc.getEntity();
            this.confidences = pc.getConfidences().clone(); // clone cached array
            sortDescending(this.confidences);
        }

        public String toBestConfString() {
            return entity + " : " + (confidences.length == 0 ? 0 : confidences[0]) + "\t";
        }

        public String getEntity() { return entity; }

        private static void sortDescending(float[] arr) {
            Arrays.sort(arr);
            // reverse in-place
            for (int i = 0, j = arr.length - 1; i < j; i++, j--) {
                float tmp = arr[i]; arr[i] = arr[j]; arr[j] = tmp;
            }
        }
    }

    // --- Component 2: The Ranking Tree (Tie-Breaker) ---
    private static class RankingNode {
        TreeMap<Double, RankingNode> children = new TreeMap<>(Collections.reverseOrder());
        List<Candidate> bucket = new ArrayList<>();

        public void insert(Candidate c, int depth) {
            if (depth >= c.confidences.length) {
                bucket.add(c);
                return;
            }
            double score = c.confidences[depth];
            children.computeIfAbsent(score, k -> new RankingNode()).insert(c, depth + 1);
        }

        public void collect(List<Candidate> results) {
            for (RankingNode child : children.values()) child.collect(results);
            results.addAll(bucket);
        }
    }

    // --- The Workflow Method ---
    public List<Candidate> getFinalRanking(Map<String, PredictionCandidate> predictions) {
        RankingNode root = new RankingNode();
        for (PredictionCandidate pc : predictions.values()) {
            root.insert(new Candidate(pc), 0);
        }
        List<Candidate> rankedResults = new ArrayList<>();
        root.collect(rankedResults);
        return rankedResults;
    }
}

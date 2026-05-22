package ruleMiningSemanticExtension.groundingEngine;

import ruleMiningSemanticExtension.domain.PredictionCandidate;

import java.util.*;

public class RankingTree {

    // --- Inner Class: The Candidate ---
    public static class Candidate {
        public String entity;
        public List<Float> confidences;

        public Candidate(String entity, List<Float> confidences) {
            this.entity = entity;
            confidences.sort(Collections.reverseOrder());
            this.confidences = confidences;
        }

        public Candidate(PredictionCandidate pc) {
            this.entity = pc.getEntity();
            this.confidences = pc.getConfidences();
            this.confidences.sort(Collections.reverseOrder());
        }

        public String toBestConfString() {
            return entity + " : " + (confidences.isEmpty() ? 0 : confidences.getFirst()) + "\t";
        }

        public String getEntity() {return entity;}
    }

    // --- Component 2: The Ranking Tree (Tie-Breaker) ---
    private static class RankingNode {
        TreeMap<Double, RankingNode> children = new TreeMap<>(Collections.reverseOrder());
        List<Candidate> bucket = new ArrayList<>();

        public void insert(Candidate c, int depth) {
            if (depth >= c.confidences.size()) {
                bucket.add(c);
                return;
            }

            double score = c.confidences.get(depth);

            children.putIfAbsent(score, new RankingNode());
            children.get(score).insert(c, depth + 1);
        }

        public void collect(List<Candidate> results) {
            for (RankingNode child : children.values()) {
                child.collect(results);
            }
            results.addAll(bucket);
        }
    }

    // --- The Workflow Method ---
    public List<Candidate> getFinalRanking(Map<String, PredictionCandidate> predictions) {
        RankingNode root = new RankingNode();

        for (PredictionCandidate pc : predictions.values()) {
            Candidate candidate = new Candidate(pc);
            root.insert(candidate, 0);
        }

        List<Candidate> rankedResults = new ArrayList<>();
        root.collect(rankedResults);

        return rankedResults;
    }

}

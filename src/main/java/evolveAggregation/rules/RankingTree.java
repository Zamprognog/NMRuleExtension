package evolveAggregation.rules;

import java.util.*;

import java.util.*;

public class RankingTree {

    // --- Inner Class: The Candidate ---
    // Holds the entity string and the accumulating list of scores
    public static class Candidate {
        public String entity;
        List<Float> confidences;

        public Candidate(String entity, TreeSet<Float> confidences) {
            this.entity = entity;
            this.confidences = new ArrayList<>(confidences.descendingSet());
        }

        public String toBestConfString() {
            return entity + " : " + confidences.getFirst() + "\t";
        }

        public String getEntity() {return entity;}
    }

    // --- Component 2: The Ranking Tree (Tie-Breaker) ---
    private static class RankingNode {
        // TreeMap keeps keys (confidences) sorted High -> Low
        TreeMap<Double, RankingNode> children = new TreeMap<>(Collections.reverseOrder());
        List<Candidate> bucket = new ArrayList<>();

        public void insert(Candidate c, int depth) {
            // If we have exhausted the scores, this candidate lives here
            if (depth >= c.confidences.size()) {
                bucket.add(c);
                return;
            }

            double score = c.confidences.get(depth);

            // Navigate down the tree, creating nodes as needed
            children.putIfAbsent(score, new RankingNode());
            children.get(score).insert(c, depth + 1);
        }

        public void collect(List<Candidate> results) {

            // 1. Recursively add children (TreeMap ensures high scores are visited first)
            for (RankingNode child : children.values()) {
                child.collect(results);
            }
            // 2. Add candidates that end exactly here
            results.addAll(bucket);

        }
    }

    // --- The Workflow Method ---
    public List<Candidate> getFinalRanking(Map<String, TreeSet<Float>> predictions) {
        RankingNode root = new RankingNode();

        for (Map.Entry<String, TreeSet<Float>> prediction : predictions.entrySet()) {

            Candidate candidate = new Candidate(prediction.getKey(), prediction.getValue());
            root.insert(candidate, 0);
        }

        // 2. Flatten the tree into a list
        List<Candidate> rankedResults = new ArrayList<>();
        root.collect(rankedResults);


        return rankedResults;
    }

}
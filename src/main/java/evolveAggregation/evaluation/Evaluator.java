package evolveAggregation.evaluation;

import evolveAggregation.groundingEngine.GroundingEngine;
import evolveAggregation.groundingEngine.RankingTree;
import evolveAggregation.groundingEngine.RuleRegistry;
import evolveAggregation.rules.Rule;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;

public class Evaluator {

    private final GroundingEngine engine;
    private final RuleRegistry registry;
    private final Set<String> allKnownFacts;

    // NEW: Indexes to quickly count known facts for filtering buffers
    private final Map<String, Integer> knownObjectCounts = new HashMap<>();
    private final Map<String, Integer> knownSubjectCounts = new HashMap<>();

    public Evaluator(GroundingEngine engine, RuleRegistry registry, Set<String> allKnownFacts) {
        this.engine = engine;
        this.registry = registry;
        this.allKnownFacts = allKnownFacts;
        buildFactIndexes();
    }

    private void buildFactIndexes() {
        for (String fact : allKnownFacts) {
            String[] parts = fact.split("\t");
            if (parts.length >= 3) {
                String subPred = parts[0] + "\t" + parts[1];
                String predObj = parts[1] + "\t" + parts[2];

                knownObjectCounts.put(subPred, knownObjectCounts.getOrDefault(subPred, 0) + 1);
                knownSubjectCounts.put(predObj, knownSubjectCounts.getOrDefault(predObj, 0) + 1);
            }
        }
    }

    public Metrics evaluate(String testPath, int limitN) {
        int hits1 = 0, hits5 = 0, hits10 = 0;
        double mrr = 0.0;
        int totalPredictions = 0;

        // We only care about the top 100 ranks
        int K_RANKS = 100;

        try (BufferedReader reader = new BufferedReader(new FileReader(testPath))) {
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null && count < limitN) {
//                if (count % 100 == 0) System.out.println("Evaluating line " + count);
                String[] parts = line.split("\\s+");
                if (parts.length < 3) continue;
                String subject = parts[0];
                String predicate = parts[1];
                String object = parts[2];

                List<Rule> candidateRules = registry.getPredictingRules(predicate);

                // CRITICAL: Rules MUST be sorted by confidence descending for early stopping to work
                candidateRules.sort((r1, r2) -> Float.compare(r2.getConfidence(), r1.getConfidence()));

                // --- Object prediction ---
                Map<String, TreeSet<Float>> objectPredictions = new HashMap<>();

                // Calculate the exact safe stopping threshold: K + (number of known facts we will filter out)
                String subPredKey = subject + "\t" + predicate;
                int objectStopThreshold = K_RANKS + knownObjectCounts.getOrDefault(subPredKey, 0);

                for (Rule r : candidateRules) {
                    r.apply(engine, true, subject, predicate, objectPredictions);

                    // SMART STOPPING
                    if (objectPredictions.size() >= objectStopThreshold) {
                        break;
                    }
                }

                int objectRank = calculateFilteredRank(objectPredictions, subject, predicate, object, true, K_RANKS);
                if (objectRank != -1) {
                    if (objectRank <= 1) hits1++;
                    if (objectRank <= 5) hits5++;
                    if (objectRank <= 10) hits10++;
                    mrr += 1.0 / objectRank;
                }
                totalPredictions++;

                // --- Subject prediction ---
                Map<String, TreeSet<Float>> subjectPredictions = new HashMap<>();

                String predObjKey = predicate + "\t" + object;
                int subjectStopThreshold = K_RANKS + knownSubjectCounts.getOrDefault(predObjKey, 0);

                for (Rule r : candidateRules) {
                    r.apply(engine, false, object, predicate, subjectPredictions);

                    // SMART STOPPING
                    if (subjectPredictions.size() >= subjectStopThreshold) {
                        break;
                    }
                }

                int subjectRank = calculateFilteredRank(subjectPredictions, object, predicate, subject, false, K_RANKS);
                if (subjectRank != -1) {
                    if (subjectRank <= 1) hits1++;
                    if (subjectRank <= 5) hits5++;
                    if (subjectRank <= 10) hits10++;
                    mrr += 1.0 / subjectRank;
                }
                totalPredictions++;

                count++;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (totalPredictions > 0) {
            return new Metrics((double) hits1 / totalPredictions, (double) hits5 / totalPredictions,
                    (double) hits10 / totalPredictions, mrr / totalPredictions, totalPredictions);
        }

        return new Metrics(0, 0, 0, 0, 0);
    }

    private int calculateFilteredRank(Map<String, TreeSet<Float>> predictions, String sourceEntity,
                                      String predicate, String correctEntity, boolean predictingObject, int maxRanks) {
        if (predictions.isEmpty()) return -1;

        RankingTree tree = new RankingTree();
        List<RankingTree.Candidate> sortedCandidates = tree.getFinalRanking(predictions);

        int rank = 1;
        for (RankingTree.Candidate candidate : sortedCandidates) {
            if (rank > maxRanks) return -1; // Give up if it falls outside the top K

            String predictedEntity = candidate.entity;

            if (predictedEntity.equals(correctEntity)) {
                return rank;
            }

            String factString = predictingObject
                    ? sourceEntity + "\t" + predicate + "\t" + predictedEntity
                    : predictedEntity + "\t" + predicate + "\t" + sourceEntity;

            if (!allKnownFacts.contains(factString)) {
                rank++;
            }
        }
        return -1;
    }
}
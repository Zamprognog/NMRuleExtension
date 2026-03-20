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

    public Evaluator(GroundingEngine engine, RuleRegistry registry, Set<String> allKnownFacts) {
        this.engine = engine;
        this.registry = registry;
        this.allKnownFacts = allKnownFacts;
    }

    public Metrics evaluate(String testPath, int limitN) {
        int hits1 = 0, hits5 = 0, hits10 = 0;
        double mrr = 0.0;
        int totalPredictions = 0;

        try (BufferedReader reader = new BufferedReader(new FileReader(testPath))) {
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null && count < limitN) {
                String[] parts = line.split("\\s+");
                if (parts.length < 3) continue;
                String subject = parts[0];
                String predicate = parts[1];
                String object = parts[2];

                List<Rule> candidateRules = registry.getPredictingRules(predicate);

                // --- Object prediction ---
                Map<String, TreeSet<Float>> objectPredictions = new HashMap<>();
                for (Rule r : candidateRules) {
                    r.apply(engine, true, subject, predicate, objectPredictions);
                    if (objectPredictions.size() > 100) break;
                }
                int objectRank = calculateFilteredRank(objectPredictions, subject, predicate, object, true);
                if (objectRank != -1) {
                    if (objectRank <= 1) hits1++;
                    if (objectRank <= 5) hits5++;
                    if (objectRank <= 10) hits10++;
                    mrr += 1.0 / objectRank;
                }
                totalPredictions++;

                // --- Subject prediction ---
                Map<String, TreeSet<Float>> subjectPredictions = new HashMap<>();
                for (Rule r : candidateRules) {
                    r.apply(engine, false, object, predicate, subjectPredictions);
                    if (subjectPredictions.size() > 500) break;
                }
                int subjectRank = calculateFilteredRank(subjectPredictions, object, predicate, subject, false);
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
            double h1 = (double) hits1 / totalPredictions;
            double h5 = (double) hits5 / totalPredictions;
            double h10 = (double) hits10 / totalPredictions;
            double finalMrr = mrr / totalPredictions;
            System.out.printf("  Evaluated %d predictions.\n", totalPredictions);
            return new Metrics(h1, h5, h10, finalMrr, totalPredictions);
        }

        return new Metrics(0, 0, 0, 0, 0);
    }

    private int calculateFilteredRank(Map<String, TreeSet<Float>> predictions,
                                      String sourceEntity,
                                      String predicate,
                                      String correctEntity,
                                      boolean predictingObject) {
        if (predictions.isEmpty()) return -1;

        RankingTree tree = new RankingTree();
        List<RankingTree.Candidate> sortedCandidates = tree.getFinalRanking(predictions);

        int rank = 1;
        for (RankingTree.Candidate candidate : sortedCandidates) {
            String predictedEntity = candidate.entity;

            if (predictedEntity.equals(correctEntity)) {
                return rank; // Found it!
            }

            String factString = predictingObject
                    ? sourceEntity + "\t" + predicate + "\t" + predictedEntity
                    : predictedEntity + "\t" + predicate + "\t" + sourceEntity;

            // Filter out known facts so they don't penalize the rank
            if (!allKnownFacts.contains(factString)) {
                rank++;
            }
        }
        return -1;
    }
}
package evolveAggregation;

import evolveAggregation.groundingEngine.GraphManager;
import evolveAggregation.groundingEngine.GroundingEngine;
import evolveAggregation.groundingEngine.RuleRegistry;
import evolveAggregation.groundingEngine.SemanticGraphManager;
import evolveAggregation.groundingEngine.SemanticGroundingEngine;
import evolveAggregation.rules.Rule;
import evolveAggregation.rules.RankingTree;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;

public class ModelComparison {

    public static void main(String[] args) {
        int N = 20000;

        // --- File Paths ---
        String graphPath     = "data/NELL995/data/NELL995_train.tsv";
        String validPath     = "data/NELL995/data/NELL995_valid.tsv"; // Adjust if you don't have this
        String testPath      = "data/NELL995/data/NELL995_test.tsv";
        String rulesPath     = "data/NELL995/rules/NELL995_rules_anyburl-1000";
//        String rulesPath     = "data/NELL995/rules/NELL995_rules_all_anyburl-100";
        String ontologyPath  = "data/NELL995/data/NELL.ontology.ttl";
        String entityTypesPath = "data/NELL995/data/NELL995_entity_types.nt";

        // 1. Load Known Facts for Filtered Evaluation
        Set<String> allKnownFacts = new HashSet<>();
        loadFactsIntoSet(allKnownFacts, graphPath);
        loadFactsIntoSet(allKnownFacts, validPath);
        loadFactsIntoSet(allKnownFacts, testPath);
        System.out.println("Loaded " + allKnownFacts.size() + " known facts for filtered metrics.\n");

        // 2. Setup Standard Engine
        System.out.println("Initializing Standard Engine...");
        GraphManager standardGm = new GraphManager();
        standardGm.parseTriples(graphPath, "\t");
        standardGm.finalizeGraph();
        RuleRegistry standardRegistry = new RuleRegistry();
        standardRegistry.loadRulesFromFile(rulesPath, false);
        GroundingEngine standardEngine = new GroundingEngine(standardGm.getGraph(), standardGm.getEntityDict(), standardGm.getRelationDict());

        // 3. Set up Semantic Engine
        System.out.println("Initializing Semantic Engine...");
        SemanticGraphManager semanticGm = new SemanticGraphManager();
        semanticGm.parseTriples(graphPath, "\t");
        semanticGm.finalizeGraph();
        semanticGm.compileConstraints(ontologyPath, entityTypesPath);
        RuleRegistry semanticRegistry = new RuleRegistry();
        semanticRegistry.loadRulesFromFile(rulesPath, false); // Assuming identical rules
        SemanticGroundingEngine semanticEngine = new SemanticGroundingEngine(semanticGm);

        System.out.println("\n==================================================");
        System.out.println("Running Standard Link Prediction...");
        System.out.println("==================================================");
        Metrics standardMetrics = evaluateModel(standardEngine, standardRegistry, testPath, N, allKnownFacts);

        System.out.println("\n==================================================");
        System.out.println("Running Semantic Link Prediction...");
        System.out.println("==================================================");
        Metrics semanticMetrics = evaluateModel(semanticEngine, semanticRegistry, testPath, N, allKnownFacts);

        // 4. Print Comparison Table
        System.out.println("\n==================================================");
        System.out.println("                FINAL COMPARISON                  ");
        System.out.println("==================================================");
        System.out.printf("%-15s | %-10s | %-10s | %-10s | %-10s%n", "Model", "Hits@1", "Hits@5", "Hits@10", "MRR");
        System.out.println("------------------------------------------------------------------");
        System.out.printf("%-15s | %-10.4f | %-10.4f | %-10.4f | %-10.4f%n", "Standard",
                standardMetrics.getHits1(), standardMetrics.getHits5(), standardMetrics.getHits10(), standardMetrics.getMrr());
        System.out.printf("%-15s | %-10.4f | %-10.4f | %-10.4f | %-10.4f%n", "Semantic",
                semanticMetrics.getHits1(), semanticMetrics.getHits5(), semanticMetrics.getHits10(), semanticMetrics.getMrr());
    }

    /**
     * Reusable evaluation loop to ensure both models are tested exactly the same way.
     */
    private static Metrics evaluateModel(GroundingEngine engine, RuleRegistry registry, String testPath, int N, Set<String> allKnownFacts) {
        int hits1 = 0, hits5 = 0, hits10 = 0;
        double mrr = 0.0;
        int totalPredictions = 0;

        try (BufferedReader reader = new BufferedReader(new FileReader(testPath))) {
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null && count < N) {
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
                    if (objectPredictions.size() > 500) break;
                }
                int objectRank = calculateFilteredRank(objectPredictions, subject, predicate, object, true, allKnownFacts);
                totalPredictions++;
                if (objectRank != -1) {

                    if (objectRank <= 1) hits1++;
                    if (objectRank <= 5) hits5++;
                    if (objectRank <= 10) hits10++;
                    mrr += 1.0 / objectRank;
                }

                // --- Subject prediction ---
                Map<String, TreeSet<Float>> subjectPredictions = new HashMap<>();
                for (Rule r : candidateRules) {
                    r.apply(engine, false, object, predicate, subjectPredictions);
                    if (subjectPredictions.size() > 500) break;
                }
                int subjectRank = calculateFilteredRank(subjectPredictions, object, predicate, subject, false, allKnownFacts);
                totalPredictions++;
                if (subjectRank != -1) {
                    if (subjectRank <= 1) hits1++;
                    if (subjectRank <= 5) hits5++;
                    if (subjectRank <= 10) hits10++;
                    mrr += 1.0 / subjectRank;
                }

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
            return new Metrics(h1, h5, h10, finalMrr);
        }

        return new Metrics(0,0,0,0);
    }

    /**
     * Calculates the FILTERED rank using the RankingTree.
     */
    private static int calculateFilteredRank(Map<String, TreeSet<Float>> predictions,
                                             String sourceEntity,
                                             String predicate,
                                             String correctEntity,
                                             boolean predictingObject,
                                             Set<String> allKnownFacts) {
        if (predictions.isEmpty()) return -1;

        RankingTree tree = new RankingTree();
        List<RankingTree.Candidate> sortedCandidates = tree.getFinalRanking(predictions);

        int rank = 1;
        for (RankingTree.Candidate candidate : sortedCandidates) {
            String predictedEntity = candidate.entity;

            if (predictedEntity.equals(correctEntity)) {
                return rank;
            }

            String factString = predictingObject
                    ? sourceEntity + "\t" + predicate + "\t" + predictedEntity
                    : predictedEntity + "\t" + predicate + "\t" + sourceEntity;

            // If it's a known true fact, we filter it out (don't penalize the rank).
            if (!allKnownFacts.contains(factString)) {
                rank++;
            }
        }
        return -1;
    }

    private static void loadFactsIntoSet(Set<String> facts, String path) {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 3) {
                    facts.add(parts[0] + "\t" + parts[1] + "\t" + parts[2]);
                }
            }
        } catch (IOException e) {
            System.err.println("Note: Could not load facts from " + path + " (File might not exist)");
        }
    }

    // Small helper class to return multiple metrics
    private static class Metrics {
        double hits1, hits5, hits10, mrr;
        Metrics(double h1, double h5, double h10, double mrr) {
            this.hits1 = h1; this.hits5 = h5; this.hits10 = h10; this.mrr = mrr;
        }
        double getHits1() { return hits1; }
        double getHits5() { return hits5; }
        double getHits10() { return hits10; }
        double getMrr() { return mrr; }
    }
}
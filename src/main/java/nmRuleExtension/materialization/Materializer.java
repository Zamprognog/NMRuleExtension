package nmRuleExtension.materialization;

import nmRuleExtension.groundingEngine.GroundingEngine;
import nmRuleExtension.groundingEngine.RuleRegistry;
import nmRuleExtension.rules.Rule;
import nmRuleExtension.utils.DualLogger;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

public class Materializer {

    private final GroundingEngine engine;
    private final RuleRegistry registry;
    private final Set<String> allKnownFacts;

    public Materializer(GroundingEngine engine, RuleRegistry registry, Set<String> allKnownFacts) {
        this.engine = engine;
        this.registry = registry;
        this.allKnownFacts = allKnownFacts;
    }

    /**
     * Materializes new triples until a target percentage of the original graph size is reached.
     * @param percentage Increase percentage (e.g., 10.0 for 10%, 30.0 for 30%)
     * @param outputPath Path to save the new triples in .nt format
     */
    public void materializeByTripleCount(double percentage, String outputPath) {
        long startTime = System.currentTimeMillis();
        int targetNewCount = (int) (allKnownFacts.size() * (percentage / 100.0));
        System.out.printf("--- Mode: TARGET TRIPLE COUNT ---%n");
        System.out.printf("Original graph size: %d. Target new triples (%.1f%%): %d%n",
                allKnownFacts.size(), percentage, targetNewCount);

        Set<String> newlyMaterialized = new HashSet<>();
        List<Rule> sortedRules = registry.getAllRulesSortedByConfidence();
        int totalEntities = engine.entityCount();

        System.out.println("Starting materialization using " + sortedRules.size() + " rules...");

        int rulesWithTriplesCount = 0;
        for (Rule rule : sortedRules) {
            if (newlyMaterialized.size() >= targetNewCount) break;

            int triplesBeforeRule = newlyMaterialized.size();
            applyRule(rule, newlyMaterialized, targetNewCount, totalEntities);

            if (newlyMaterialized.size() > triplesBeforeRule) {
                rulesWithTriplesCount++;
            }
            DualLogger.getOriginalOut().println("Rule applied. Current new triples: " + newlyMaterialized.size());
        }

        finishMaterialization(startTime, rulesWithTriplesCount, newlyMaterialized, outputPath);
    }

    /**
     * Materializes new triples using only the top N% of rules ranked by confidence.
     * @param rulePercentage Percentage of rules to use (e.g., 10.0 for top 10% of rules)
     * @param outputPath Path to save the new triples in .nt format
     */
    public void materializeByRuleConfidence(double rulePercentage, String outputPath) {
        long startTime = System.currentTimeMillis();
        List<Rule> allRules = registry.getAllRulesSortedByConfidence();
        int numRulesToUse = (int) Math.ceil(allRules.size() * (rulePercentage / 100.0));
        
        System.out.printf("--- Mode: TOP N%% RULES BY CONFIDENCE ---%n");
        System.out.printf("Total rules available: %d. Using top %.1f%% (%d rules).%n",
                allRules.size(), rulePercentage, numRulesToUse);

        Set<String> newlyMaterialized = new HashSet<>();
        int totalEntities = engine.entityCount();

        int rulesWithTriplesCount = 0;
        for (int i = 0; i < numRulesToUse; i++) {
            Rule rule = allRules.get(i);
            int triplesBeforeRule = newlyMaterialized.size();
            
            applyRule(rule, newlyMaterialized, Integer.MAX_VALUE, totalEntities);

            if (newlyMaterialized.size() > triplesBeforeRule) {
                rulesWithTriplesCount++;
            }
            DualLogger.getOriginalOut().println("Rule " + (i+1) + "/" + numRulesToUse + " applied. Total new triples: " + newlyMaterialized.size());
        }

        finishMaterialization(startTime, rulesWithTriplesCount, newlyMaterialized, outputPath);
    }

    private void applyRule(Rule rule, Set<String> newlyMaterialized, int targetNewCount, int totalEntities) {
        String predicate = rule.getHead().getPredicate();
        // Try applying this rule to every entity in the graph
        for (int i = 0; i < totalEntities; i++) {
            if (newlyMaterialized.size() >= targetNewCount) break;

            String entity = engine.idToEntity(i);

            // 1. Try predicting the object (Forward)
            Map<String, List<Float>> objPreds = new HashMap<>();
            rule.apply(engine, true, entity, predicate, objPreds);
            processPredictions(entity, predicate, objPreds.keySet(), true, newlyMaterialized, targetNewCount);

            if (newlyMaterialized.size() >= targetNewCount) break;

            // 2. Try predicting the subject (Backward)
            Map<String, List<Float>> subjPreds = new HashMap<>();
            rule.apply(engine, false, entity, predicate, subjPreds);
            processPredictions(entity, predicate, subjPreds.keySet(), false, newlyMaterialized, targetNewCount);
        }
    }

    private void finishMaterialization(long startTime, int rulesWithTriplesCount, Set<String> newlyMaterialized, String outputPath) {
        long endTime = System.currentTimeMillis();
        long elapsedTime = endTime - startTime;

        System.out.println("Materialization complete. Writing to file...");
        System.out.println("Applied rules that added triples: " + rulesWithTriplesCount);
        System.out.printf("Total elapsed time for materialization: %d ms (%.2f seconds)%n", elapsedTime, elapsedTime / 1000.0);

        writeToNTriples(newlyMaterialized, outputPath);
    }

    /**
     * Legacy method for backward compatibility, defaults to triple count materialization.
     * @deprecated Use {@link #materializeByTripleCount(double, String)} or {@link #materializeByRuleConfidence(double, String)}
     */
    @Deprecated
    public void materialize(double percentage, String outputPath) {
        materializeByTripleCount(percentage, outputPath);
    }

    private void processPredictions(String sourceEntity, String predicate, Set<String> predictedEntities,
                                    boolean predictingObject, Set<String> newlyMaterialized, int targetNewCount) {
        for (String predicted : predictedEntities) {
            if (newlyMaterialized.size() >= targetNewCount) return;

            // Construct the fact exactly as it would appear in the DataLoader
            String fact = predictingObject
                    ? sourceEntity + "\t" + predicate + "\t" + predicted
                    : predicted + "\t" + predicate + "\t" + sourceEntity;

            // Only add if it doesn't already exist in the original graph OR the newly generated set
            if (!allKnownFacts.contains(fact) && !newlyMaterialized.contains(fact)) {
                newlyMaterialized.add(fact);
            }
        }
    }

    private void writeToNTriples(Set<String> facts, String outputPath) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputPath))) {
            for (String fact : facts) {
                String[] parts = fact.split("\t");
                // .nt format: <subject> <predicate> <object> .
                // Wrapping in angle brackets as is standard for N-Triples URIs
                String ntLine = String.format("<%s> <%s> <%s> .%n", parts[0], parts[1], parts[2]);
                writer.write(ntLine);
            }
            System.out.println("Successfully wrote " + facts.size() + " triples to " + outputPath);
        } catch (IOException e) {
            System.err.println("Error writing to file: " + e.getMessage());
        }
    }
}
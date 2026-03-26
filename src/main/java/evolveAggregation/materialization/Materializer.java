package evolveAggregation.materialization;

import evolveAggregation.groundingEngine.GroundingEngine;
import evolveAggregation.groundingEngine.RuleRegistry;
import evolveAggregation.rules.Rule;

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
     * * @param percentage Increase percentage (e.g., 10.0 for 10%, 30.0 for 30%)
     * @param outputPath Path to save the new triples in .nt format
     */
    public void materialize(double percentage, String outputPath) {
        int targetNewCount = (int) (allKnownFacts.size() * (percentage / 100.0));
        System.out.printf("Original graph size: %d. Target new triples (%.1f%%): %d%n",
                allKnownFacts.size(), percentage, targetNewCount);

        Set<String> newlyMaterialized = new HashSet<>();
        List<Rule> sortedRules = registry.getAllRulesSortedByConfidence();
        int totalEntities = engine.entityCount();

        System.out.println("Starting materialization using " + sortedRules.size() + " rules...");

        for (Rule rule : sortedRules) {
            if (newlyMaterialized.size() >= targetNewCount) break;

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
            System.out.println("Rule applied. Current new triples: " + newlyMaterialized.size());
        }

        System.out.println("Materialization complete. Writing to file...");
        writeToNTriples(newlyMaterialized, outputPath);
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
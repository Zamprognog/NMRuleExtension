package ruleMiningSemanticExtension.aggregation.learning;

import io.jenetics.prog.ProgramGene;
import org.junit.jupiter.api.Test;
import ruleMiningSemanticExtension.aggregation.PredictionPool;
import ruleMiningSemanticExtension.domain.PredictionCandidate;
import ruleMiningSemanticExtension.graphTools.SemanticGraphManager;
import ruleMiningSemanticExtension.groundingEngine.GroundingEngine;
import ruleMiningSemanticExtension.groundingEngine.RuleRegistry;
import ruleMiningSemanticExtension.rules.Rule;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SymbolicAggregatorTest {

    @Test
    public void testSymbolicEvolutionOnNell() throws IOException {
        String projectRoot = findProjectRoot();
        String trainPath = projectRoot + "data/NELL995/data/NELL995_train.tsv";
        String rulesPath = projectRoot + "data/NELL995/rules/NELL995_rules_anyburl_ALL-100";
        String validPath = projectRoot + "data/NELL995/data/NELL995_test.tsv";

        // 1. Load Graph
        SemanticGraphManager gm = new SemanticGraphManager();
        gm.parseTriples(trainPath, "\t");
        gm.finalizeGraph();
        GroundingEngine engine = new GroundingEngine(gm);

        FeatureExtractor.setSemanticGraphManager(gm);

        // 2. Load Rules (top 100)
        RuleRegistry registry = new RuleRegistry();
        int count = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(rulesPath))) {
            String line;
            while ((line = reader.readLine()) != null && count < 100) {
                if (line.trim().isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\t");
                if (parts.length >= 4) {
                    float confidence = (Float.parseFloat(parts[1])) / (Float.parseFloat(parts[0]) + 5);
                    String ruleStr = parts[3];
                    registry.loadRuleFromString(parts[0] + "\t" + parts[1] + "\t" + confidence + "\t" + ruleStr);
                    count++;
                }
            }
        }

        // 3. Create Validation Set
        List<AggregatorFitnessFunction.ValidationQuery> validationSet = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(validPath))) {
            String line;
            int queriesCount = 0;
            while ((line = reader.readLine()) != null && queriesCount < 10) { 
                String[] parts = line.split("\t");
                if (parts.length >= 3) {
                    String testSubject = parts[0];
                    String testPredicate = parts[1];
                    String testObject = parts[2];

                    List<Rule> predictingRules = registry.getPredictingRules(testPredicate);
                    if (predictingRules.isEmpty()) continue;

                    Map<String, PredictionCandidate> predictions = new HashMap<>();
                    for (Rule r : predictingRules) {
                        r.apply(engine, true, testSubject, testPredicate, predictions);
                    }

                    if (!predictions.isEmpty()) {
                        PredictionPool pool = new PredictionPool(predictions);
                        validationSet.add(AggregatorFitnessFunction.ValidationQuery.from(
                                testSubject + " " + testPredicate, pool, testObject));
                        queriesCount++;
                    }
                }
            }
        }

        System.out.println("Validation set size: " + validationSet.size());

        if (validationSet.isEmpty()) {
            System.out.println("No valid queries found for evolution test. Skipping.");
            return;
        }

        // 4. Run Evolution
        AggregatorEvolution<ProgramGene<Double>> evolution = new AggregatorEvolution<>();
        SymbolicAggregator prototype = new SymbolicAggregator();
        evolution.runEvolution(validationSet, prototype);

        // 5. Verify results
        System.out.println("Best evolved formula: " + prototype.toString());
        assertNotNull(prototype.toString());
        assertTrue(!prototype.toString().equals("empty"));
    }

    private String findProjectRoot() {
        if (new File("data").exists()) return "./";
        File current = new File(".").getAbsoluteFile();
        while (current != null && !new File(current, "data").exists()) {
            current = current.getParentFile();
        }
        return (current != null) ? current.getAbsolutePath() + "/" : "./";
    }
}

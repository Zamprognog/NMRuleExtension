package evolveAggregation;

import evolveAggregation.graphTools.GraphManager;
import evolveAggregation.graphTools.SemanticGraphManager;
import evolveAggregation.groundingEngine.GroundingEngine;
import evolveAggregation.groundingEngine.RuleRegistry;
import evolveAggregation.evaluation.Evaluator;
import evolveAggregation.evaluation.Metrics;
import evolveAggregation.groundingEngine.SemanticGroundingEngine;
import evolveAggregation.utils.DataLoader;

import java.util.HashSet;
import java.util.Set;

public class Main {

    public static void main(String[] args) {
        int N = 20000;

        // --- File Paths ---
        String graphPath     = "data/NELL995/data/NELL995_train.tsv";
        String validPath     = "data/NELL995/data/NELL995_valid.tsv";
        String testPath      = "data/NELL995/data/NELL995_test.tsv";
        String rulesPath     = "data/NELL995/rules/NELL995_rules_anyburl-1000";

        // 1. Load Known Facts for Filtered Evaluation
        Set<String> allKnownFacts = new HashSet<>();
        DataLoader.loadFactsIntoSet(allKnownFacts, graphPath, validPath, testPath);
        Set<String> trainKnownFacts = new HashSet<>();
        DataLoader.loadFactsIntoSet(trainKnownFacts, graphPath);
        System.out.println("Loaded " + allKnownFacts.size() + " known facts for filtered metrics.");
        System.out.println("Loaded " + trainKnownFacts.size() + " training facts for consistency checks.\n");

        // 2. Initialize Engine
        System.out.println("Initializing Engine...");
        SemanticGraphManager gm = new SemanticGraphManager();
        gm.parseTriples(graphPath, "\t");
        gm.finalizeGraph();
        gm.compileConstraints("data/NELL995/data/NELL.ontology.ttl", "data/NELL995/data/NELL995_entity_types.nt");


        RuleRegistry registry = new RuleRegistry();
        registry.loadRulesFromFile(rulesPath, false);
        GroundingEngine engine = new GroundingEngine(gm);
//        SemanticGroundingEngine engine = new SemanticGroundingEngine(gm);

        Evaluator evaluator = new Evaluator(engine, registry, allKnownFacts, trainKnownFacts, gm);

        // 3. Evaluate
        System.out.println("\n==================================================");
        System.out.println("Running Link Prediction...");
        System.out.println("==================================================");
        Metrics metrics = evaluator.evaluate(testPath, N);

        // 4. Print Results
        System.out.println("\n==================================================");
        System.out.println("                FINAL METRICS                     ");
        System.out.println("==================================================");
        System.out.printf("%-15s | %-10s | %-10s | %-10s | %-10s | %-10s%n", "Model", "Hits@1", "Hits@5", "Hits@10", "MRR", "SEM@10");
        System.out.println("----------------------------------------------------------------------------");
        metrics.printRow("Baseline");
    }
}
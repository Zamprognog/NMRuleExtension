package evolveAggregation;

import evolveAggregation.groundingEngine.GraphManager;
import evolveAggregation.groundingEngine.GroundingEngine;
import evolveAggregation.groundingEngine.RuleRegistry;
import evolveAggregation.materialization.Materializer;
import evolveAggregation.utils.DataLoader;

import java.util.HashSet;
import java.util.Set;

public class RunMaterialization {

    public static void main(String[] args) {
        String graphPath = "data/NELL995/data/NELL995_train.tsv";
        String validPath = "data/NELL995/data/NELL995_valid.tsv";
        String testPath  = "data/NELL995/data/NELL995_test.tsv";
        String rulesPath = "data/NELL995/rules/NELL995_rules_amie.tsv";
        String outputPath = "data/NELL995/data/NELL995_materialized_10pct.nt";

        double targetPercentage = 10.0; // Generate 10% new facts

        // 1. Load Known Facts (to track duplicates and calculate percentages)
        Set<String> allKnownFacts = new HashSet<>();
        DataLoader.loadFactsIntoSet(allKnownFacts, graphPath, validPath, testPath);

        // 2. Build the unified Graph
        System.out.println("Building Unified Graph...");
        GraphManager gm = new GraphManager();
        gm.parseTriples(graphPath, "\t");
        gm.parseTriples(validPath, "\t");
        gm.parseTriples(testPath, "\t");
        gm.finalizeGraph(); // Freeze arrays only AFTER all files are loaded

        // 3. Load Rules
        RuleRegistry registry = new RuleRegistry();
        registry.loadRulesFromFile(rulesPath, true);
        GroundingEngine engine = new GroundingEngine(gm);

        // 4. Run Materialization
        Materializer materializer = new Materializer(engine, registry, allKnownFacts);
        materializer.materialize(targetPercentage, outputPath);
    }
}
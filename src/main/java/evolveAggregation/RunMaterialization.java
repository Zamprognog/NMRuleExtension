package evolveAggregation;

import evolveAggregation.graphTools.SemanticGraphManager;
import evolveAggregation.groundingEngine.GroundingEngine;
import evolveAggregation.groundingEngine.RuleRegistry;
import evolveAggregation.groundingEngine.SemanticGroundingEngine;
import evolveAggregation.materialization.Materializer;
import evolveAggregation.utils.DataLoader;
import evolveAggregation.utils.DualLogger;
import evolveAggregation.utils.ExperimentConfig;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;

public class RunMaterialization {

    public static void main(String[] args) {
        String configPath ;
        // Pass the JSON config path as an argument, or fallback to a default
        configPath = args.length > 0 ? args[0] : "data/NELL995/NELL995.json";
//        configPath = args.length > 0 ? args[0] : "data/hetionet/hetionet.json";
//        configPath = args.length > 0 ? args[0] : "data/CSKG2/CSKG2.json";
//        configPath = args.length > 0 ? args[0] : "data/YAGO4.5/YAGO4.5.json";

        // Define the target percentages for new facts
        double[] targetPercentages = {10.0, 30.0};

        try {
            // 1. Load Configuration & Setup Logging
            ExperimentConfig config = ExperimentConfig.load(configPath);
            DualLogger.setupLogger(config.predictionsDir, config.datasetName + "_materialization");

            System.out.println("==================================================");
            System.out.println("Starting Materialization for Dataset: " + config.datasetName);
            System.out.println("==================================================");

            // Ensure predictions directory exists
            File dir = new File(config.predictionsDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            // Generate timestamp once for the entire run
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

            // 2. Load Known Facts (to track duplicates and calculate percentages)
            Set<String> allKnownFacts = new HashSet<>();
            DataLoader.loadFactsIntoSet(allKnownFacts, config.train, config.valid, config.test);
            System.out.println("Loaded " + allKnownFacts.size() + " known facts.");

            // 3. Initialize Shared Semantic Graph Manager
            System.out.println("Building Unified Semantic Graph...");
            SemanticGraphManager semanticGm = new SemanticGraphManager();

            // Parse all triples to create a unified graph for materialization
            semanticGm.parseTriples(config.train, "\t");
            semanticGm.parseTriples(config.valid, "\t");
            semanticGm.parseTriples(config.test, "\t");
            semanticGm.finalizeGraph();

            // Compile semantic constraints so the Semantic Engine can use them
            semanticGm.compileConstraints(config.schema, config.typesFile);
            semanticGm.precomputeDisjointConstraints();

            // 4. Run Materialization Scenarios
            System.out.println("\n##################################################");
            System.out.println("           MATERIALIZING WITH ANYBURL RULES       ");
            System.out.println("##################################################");
            runMaterializationScenarios(config, config.anyburlRules, false, "anyburl", semanticGm, allKnownFacts, targetPercentages, timestamp);

            System.out.println("\n##################################################");
            System.out.println("             MATERIALIZING WITH AMIE RULES        ");
            System.out.println("##################################################");
            runMaterializationScenarios(config, config.amieRules, true, "amie", semanticGm, allKnownFacts, targetPercentages, timestamp);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void runMaterializationScenarios(ExperimentConfig config, String rulesPath, boolean isAmie, String rulesetName,
                                                    SemanticGraphManager gm, Set<String> allKnownFacts, double[] percentages, String timestamp) {

        // Load rules into a shared registry
        System.out.println("Loading Rules from: " + rulesPath);
        RuleRegistry registry = new RuleRegistry();
        registry.loadRulesFromFile(rulesPath, isAmie);

        // Initialize both engines using the shared SemanticGraphManager
        GroundingEngine standardEngine = new GroundingEngine(gm);
        SemanticGroundingEngine semanticEngine = new SemanticGroundingEngine(gm);

        // Iterate over the target percentages (10% and 30%)
        for (double pct : percentages) {
            int pctInt = (int) pct; // Cast to int for clean filename strings

            // --- Condition 1: Standard Grounding Engine ---
            System.out.println("\n--- Condition: Standard Grounding Engine | Target: " + pctInt + "% ---");
            String standardOutPath = String.format("%s/%s_%s_standard_%d_%s_new_triples.nt",
                    config.predictionsDir, config.datasetName, rulesetName, pctInt, timestamp);

            Materializer standardMaterializer = new Materializer(standardEngine, registry, allKnownFacts);
            standardMaterializer.materialize(pct, standardOutPath);

            // --- Condition 2: Semantic Grounding Engine ---
            System.out.println("\n--- Condition: Semantic Grounding Engine | Target: " + pctInt + "% ---");
            String semanticOutPath = String.format("%s/%s_%s_semantic_%d_%s_new_triples.nt",
                    config.predictionsDir, config.datasetName, rulesetName, pctInt, timestamp);

            Materializer semanticMaterializer = new Materializer(semanticEngine, registry, allKnownFacts);
            semanticMaterializer.materialize(pct, semanticOutPath);
        }
    }
}
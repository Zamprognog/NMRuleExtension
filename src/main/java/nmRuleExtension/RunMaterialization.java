package nmRuleExtension;

import nmRuleExtension.graphTools.SemanticGraphManager;
import nmRuleExtension.groundingEngine.GroundingEngine;
import nmRuleExtension.groundingEngine.RuleRegistry;
import nmRuleExtension.groundingEngine.SemanticGroundingEngine;
import nmRuleExtension.materialization.Materializer;
import nmRuleExtension.utils.DataLoader;
import nmRuleExtension.utils.DualLogger;
import nmRuleExtension.utils.ExperimentConfig;

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
//         configPath = args.length > 0 ? args[0] : "data/hetionet/hetionet.json";
//        configPath = args.length > 0 ? args[0] : "data/CSKG2/CSKG2.json";
//        configPath = args.length > 0 ? args[0] : "data/YAGO4.5/YAGO4.5.json";

        // Define the target percentages for new facts (Scenario A: triple count)
        double[] targetPercentages = {10.0, 30.0};
        // Define the target percentages for rules (Scenario B: rule confidence)
        double[] rulePercentages = {5.0, 10.0};

        try {
            // 1. Load Configuration
            ExperimentConfig config = ExperimentConfig.load(configPath);

            // Generate timestamp once for the entire run
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

            // Construct the specific materialization directory
            // Format: data/<dataset_name>/predictions/materialization/<timestamp>/
            String materializationDir = config.predictionsDir + "materialization/" + timestamp + "/";
            File matDir = new File(materializationDir);
            if (!matDir.exists()) {
                matDir.mkdirs();
            }

            // Setup Logging
            DualLogger.setupLogger(materializationDir, config.datasetName + "_materialization");

            System.out.println("==================================================");
            System.out.println("Starting Materialization for Dataset: " + config.datasetName);
            System.out.println("==================================================");
            System.out.println("Output Directory: " + materializationDir);

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
            String rulesToMaterialize = config.materializeRules != null ? config.materializeRules.toLowerCase() : "both";

            if (rulesToMaterialize.contains("anyburl") || rulesToMaterialize.equals("both")) {
                System.out.println("\n##################################################");
                System.out.println("           MATERIALIZING WITH ANYBURL RULES       ");
                System.out.println("##################################################");
                if (config.anyburlRules != null) {
                    System.out.println("--- Ruleset: AnyBURL FULL ---");
                    runMaterializationScenarios(config, materializationDir, config.anyburlRules, false, "anyburl_FULL", semanticGm, allKnownFacts, targetPercentages, rulePercentages, timestamp);
                }
                if (config.anyburlRulesCP != null) {
                    System.out.println("\n--- Ruleset: AnyBURL CP ---");
                    runMaterializationScenarios(config, materializationDir, config.anyburlRulesCP, false, "anyburl_CP", semanticGm, allKnownFacts, targetPercentages, rulePercentages, timestamp);
                }
            }

            if (rulesToMaterialize.contains("amie") || rulesToMaterialize.equals("both")) {
                System.out.println("\n##################################################");
                System.out.println("             MATERIALIZING WITH AMIE RULES        ");
                System.out.println("##################################################");
                if (config.amieRules != null) {
                    System.out.println("--- Ruleset: AMIE FULL ---");
                    runMaterializationScenarios(config, materializationDir, config.amieRules, true, "amie_FULL", semanticGm, allKnownFacts, targetPercentages, rulePercentages, timestamp);
                }
                if (config.amieRulesCP != null) {
                    System.out.println("\n--- Ruleset: AMIE CP ---");
                    runMaterializationScenarios(config, materializationDir, config.amieRulesCP, true, "amie_CP", semanticGm, allKnownFacts, targetPercentages, rulePercentages, timestamp);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void runMaterializationScenarios(ExperimentConfig config, String materializationDir, String rulesPath, boolean isAmie, String rulesetName,
                                                    SemanticGraphManager gm, Set<String> allKnownFacts, double[] percentages, double[] rulePercentages, String timestamp) {

        // Load rules into a shared registry
        System.out.println("Loading Rules from: " + rulesPath);
        RuleRegistry registry = new RuleRegistry();
        registry.loadRulesFromFile(rulesPath, isAmie);

        // Initialize both engines using the shared SemanticGraphManager
        GroundingEngine standardEngine = new GroundingEngine(gm);
        SemanticGroundingEngine semanticEngine = new SemanticGroundingEngine(gm);

        String groundingToRun = config.groundingType != null ? config.groundingType.toLowerCase() : "both";

        // --- Scenario 1: Materialize by Triple Count (10% and 30% of Graph) ---
        if (false) {
            System.out.println("\n>>> SCENARIO A: Materializing by Target Triple Count <<<");
            for (double pct : percentages) {
                int pctInt = (int) pct;

                if (groundingToRun.contains("standard") || groundingToRun.equals("both")) {
                    System.out.println("\n--- Condition: Standard | Target: " + pctInt + "% Triples ---");
                    String standardOutPath = String.format("%s/%s_%s_standard_%d_triples_%s.nt",
                            materializationDir, config.datasetName, rulesetName, pctInt, timestamp);

                    Materializer standardMaterializer = new Materializer(standardEngine, registry, allKnownFacts);
                    standardMaterializer.materializeByTripleCount(pct, standardOutPath);
                }

                if (groundingToRun.contains("semantic") || groundingToRun.equals("both")) {
                    System.out.println("\n--- Condition: Semantic | Target: " + pctInt + "% Triples ---");
                    String semanticOutPath = String.format("%s/%s_%s_semantic_%d_triples_%s.nt",
                            materializationDir, config.datasetName, rulesetName, pctInt, timestamp);

                    Materializer semanticMaterializer = new Materializer(semanticEngine, registry, allKnownFacts);
                    semanticMaterializer.materializeByTripleCount(pct, semanticOutPath);
                }
            }
        }

        // --- Scenario 2: Materialize by Top N% Rules (10% and 30% of Rules) ---
        System.out.println("\n>>> SCENARIO B: Materializing by Top N% Rules <<<");
        for (double pct : rulePercentages) {
            int pctInt = (int) pct;

            if (groundingToRun.contains("standard") || groundingToRun.equals("both")) {
                System.out.println("\n--- Condition: Standard | Target: Top " + pctInt + "% Rules ---");
                String standardOutPath = String.format("%s/%s_%s_standard_%d_rules_%s.nt",
                        materializationDir, config.datasetName, rulesetName, pctInt, timestamp);

                Materializer standardMaterializer = new Materializer(standardEngine, registry, allKnownFacts);
                standardMaterializer.materializeByRuleConfidence(pct, standardOutPath);
            }

            if (groundingToRun.contains("semantic") || groundingToRun.equals("both")) {
                System.out.println("\n--- Condition: Semantic | Target: Top " + pctInt + "% Rules ---");
                String semanticOutPath = String.format("%s/%s_%s_semantic_%d_rules_%s.nt",
                        materializationDir, config.datasetName, rulesetName, pctInt, timestamp);

                Materializer semanticMaterializer = new Materializer(semanticEngine, registry, allKnownFacts);
                semanticMaterializer.materializeByRuleConfidence(pct, semanticOutPath);
            }
        }
    }
}
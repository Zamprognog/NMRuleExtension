package nmRuleExtension;

import nmRuleExtension.graphTools.SemanticGraphManager;
import nmRuleExtension.groundingEngine.GroundingEngine;
import nmRuleExtension.groundingEngine.RuleRegistry;
import nmRuleExtension.groundingEngine.SemanticGroundingEngine;
import nmRuleExtension.evaluation.Evaluator;
import nmRuleExtension.evaluation.Metrics;
import nmRuleExtension.utils.DataLoader;
import nmRuleExtension.utils.ExperimentConfig;
import nmRuleExtension.utils.DualLogger;

import java.util.HashSet;
import java.util.Set;

public class RunExperiment {

    public static void main(String[] args) {
        String configPath;
        String mode = "full"; // Default mode

        // Pass the JSON config path as first argument, mode as second
//        configPath = args.length > 0 ? args[0] : "data/NELL995/NELL995.json";
        configPath = args.length > 0 ? args[0] : "data/OWL2Bench/OWL2Bench.json";
//                configPath = args.length > 0 ? args[0] : "data/hetionet/hetionet.json";
//        configPath = args.length > 0 ? args[0] : "data/CSKG2/CSKG2.json";
//        configPath = args.length > 0 ? args[0] : "data/YAGO4.5/YAGO4.5.json";

        if (args.length > 1) {
            mode = args[1].toLowerCase();
        }

        int N = 100000; // Define max evaluations

        try {
            // 1. Load Configuration
            ExperimentConfig config = ExperimentConfig.load(configPath);

            // Determine runs based on mode
            boolean runAnyBurl = mode.equals("full") || mode.equals("anyburl");
            boolean runAmie = mode.equals("full") || mode.equals("amie");

            if (runAnyBurl && ruleSetAvailable(config.anyburlRules, "AnyBURL ALL")) {
                // Setup logger for AnyBURL ALL
                DualLogger.setupLogger(config.predictionsDir, config.datasetName, "ALL");
                runFullExperiment(config, N, "anyburl_all");
            }

            if (runAnyBurl && ruleSetAvailable(config.anyburlRulesCP, "AnyBURL CP")) {
                // Setup logger for AnyBURL CP
                DualLogger.setupLogger(config.predictionsDir, config.datasetName, "CP");
                runFullExperiment(config, N, "anyburl_cp");
            }

            if (runAmie && ruleSetAvailable(config.amieRules, "AMIE ALL")) {
                // Setup logger for AMIE (standard name or another suffix if needed, here just default or AMIE)
                DualLogger.setupLogger(config.predictionsDir, config.datasetName, "AMIE");
                runFullExperiment(config, N, "amie");
            }

            if (runAmie && ruleSetAvailable(config.amieRulesCP, "AMIE CP")) {
                // Setup logger for AnyBURL CP
                DualLogger.setupLogger(config.predictionsDir, config.datasetName, "CP");
                runFullExperiment(config, N, "amie_cp");
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** Skips (with a notice) rule sets that are unset in the config, missing on disk, or empty. */
    private static boolean ruleSetAvailable(String rulesPath, String label) {
        if (rulesPath == null || rulesPath.isEmpty()) {
            System.out.println("Skipping " + label + ": not configured.");
            return false;
        }
        java.io.File file = new java.io.File(rulesPath);
        if (!file.isFile()) {
            System.out.println("Skipping " + label + ": rule file not found at " + rulesPath);
            return false;
        }
        // An empty placeholder (e.g. a rule set that could not be mined) would otherwise be
        // evaluated as a zero-rule set and reported as a row of zeros.
        if (file.length() == 0) {
            System.out.println("Skipping " + label + ": rule file is empty at " + rulesPath);
            return false;
        }
        return true;
    }

    private static void runFullExperiment(ExperimentConfig config, int N, String runType) throws Exception {
        System.out.println("==================================================");
        System.out.println("Starting Experiment Run: " + runType + " for Dataset: " + config.datasetName);
        System.out.println("==================================================");

        // 2. Load Known Facts for Filtered Evaluation
        Set<String> allKnownFacts = new HashSet<>();
        DataLoader.loadFactsIntoSet(allKnownFacts, config.train, config.valid, config.test);
        Set<String> trainKnownFacts = new HashSet<>();
        DataLoader.loadFactsIntoSet(trainKnownFacts, config.train);
        System.out.println("Loaded " + allKnownFacts.size() + " known facts for filtered metrics.");
        System.out.println("Loaded " + trainKnownFacts.size() + " training facts for consistency checks.");

        // 3. Initialize Shared Semantic Graph Manager
        System.out.println("Initializing Shared SemanticGraphManager...");
        SemanticGraphManager semanticGm = new SemanticGraphManager();
        semanticGm.parseTriples(config.train, "\t");
        semanticGm.finalizeGraph();
        semanticGm.compileConstraints(config.schema, config.typesFile);
        semanticGm.precomputeDisjointConstraints();

        // 4. Run Evaluations
        if (runType.equals("anyburl_all")) {
            System.out.println("\n##################################################");
            System.out.println("              EVALUATING ANYBURL RULES (ALL)      ");
            System.out.println("##################################################");
            runRuleSetEvaluation(config.anyburlRules, false, semanticGm, allKnownFacts, trainKnownFacts, config.test, N);
        } else if (runType.equals("anyburl_cp")) {
            System.out.println("\n##################################################");
            System.out.println("              EVALUATING ANYBURL RULES (CP)       ");
            System.out.println("##################################################");
            runRuleSetEvaluation(config.anyburlRulesCP, false, semanticGm, allKnownFacts, trainKnownFacts, config.test, N);
        } else if (runType.equals("amie")) {
            System.out.println("\n##################################################");
            System.out.println("                EVALUATING AMIE RULES (4CP)            ");
            System.out.println("##################################################");
            runRuleSetEvaluation(config.amieRules, true, semanticGm, allKnownFacts, trainKnownFacts, config.test, N);
        } else if (runType.equals("amie_cp")) {
            System.out.println("\n##################################################");
            System.out.println("                EVALUATING AMIE RULES (3CP)           ");
            System.out.println("##################################################");
            runRuleSetEvaluation(config.amieRulesCP, true, semanticGm, allKnownFacts, trainKnownFacts, config.test, N);
        }
    }

    static void runRuleSetEvaluation(String rulesPath, boolean isAmie, SemanticGraphManager gm,
                                     Set<String> allKnownFacts, Set<String> trainKnownFacts, String testPath, int maxEvals) {

        // Load rules into a shared registry
        System.out.println("Loading Rules from: " + rulesPath);
        RuleRegistry registry = new RuleRegistry();
        registry.loadRulesFromFile(rulesPath, isAmie);

        // --- Standard Engine ---
        System.out.println("\n--- Condition 1: Standard Grounding Engine ---");
        GroundingEngine standardEngine = new GroundingEngine(gm);
        Evaluator standardEvaluator = new Evaluator(standardEngine, registry, allKnownFacts, trainKnownFacts, gm);
        Metrics standardMetrics = standardEvaluator.evaluate(testPath, maxEvals);

        // --- Semantic Engine ---
        System.out.println("\n--- Condition 2: Semantic Grounding Engine ---");
        SemanticGroundingEngine semanticEngine = new SemanticGroundingEngine(gm);
        Evaluator semanticEvaluator = new Evaluator(semanticEngine, registry, allKnownFacts, trainKnownFacts, gm);
        Metrics semanticMetrics = semanticEvaluator.evaluate(testPath, maxEvals);

        // --- Print Local Comparison ---
        System.out.println("\n==================================================");
        System.out.println("   RESULTS: " + (isAmie ? "AMIE" : "AnyBURL") + " Rules");
        System.out.println("==================================================");
        // Must match Metrics.printRow(): Hits@1, Hits@5, Hits@10, MRR, Sem@10, Sem@100
        System.out.printf("%-15s | %-10s | %-10s | %-10s | %-10s | %-10s | %-10s%n",
                "Engine", "Hits@1", "Hits@5", "Hits@10", "MRR", "Sem@10", "Sem@100");
        System.out.println("---------------------------------------------------------------------------------------------");

        // Use printRow from Metrics (adjust strings to fit your column widths if necessary)
        standardMetrics.printRow("Standard");
        semanticMetrics.printRow("Semantic");
    }
}
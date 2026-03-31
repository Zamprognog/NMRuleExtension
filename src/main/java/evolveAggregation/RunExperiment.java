package evolveAggregation;

import evolveAggregation.graphTools.SemanticGraphManager;
import evolveAggregation.groundingEngine.GroundingEngine;
import evolveAggregation.groundingEngine.RuleRegistry;
import evolveAggregation.groundingEngine.SemanticGroundingEngine;
import evolveAggregation.evaluation.Evaluator;
import evolveAggregation.evaluation.Metrics;
import evolveAggregation.utils.DataLoader;
import evolveAggregation.utils.ExperimentConfig;
import evolveAggregation.utils.DualLogger;

import java.util.HashSet;
import java.util.Set;

public class RunExperiment {

    public static void main(String[] args) {
        String configPath ;
        // Pass the JSON config path as an argument, or fallback to a default
//        configPath = args.length > 0 ? args[0] : "data/NELL995/NELL995.json";
//        configPath = args.length > 0 ? args[0] : "data/hetionet/hetionet.json";
        configPath = args.length > 0 ? args[0] : "data/CSKG2/CSKG2.json";
//        configPath = args.length > 0 ? args[0] : "data/YAGO4.5/YAGO4.5.json";
        int N = 30000; // Define max evaluations

        try {
            // 1. Load Configuration & Setup Logging
            ExperimentConfig config = ExperimentConfig.load(configPath);
            DualLogger.setupLogger(config.predictionsDir, config.datasetName);

            System.out.println("==================================================");
            System.out.println("Starting Experiment for Dataset: " + config.datasetName);
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
            System.out.println("\n##################################################");
            System.out.println("              EVALUATING ANYBURL RULES            ");
            System.out.println("##################################################");
            runRuleSetEvaluation(config.anyburlRules, false, semanticGm, allKnownFacts, trainKnownFacts, config.test, N);

            System.out.println("\n##################################################");
            System.out.println("                EVALUATING AMIE RULES             ");
            System.out.println("##################################################");
            runRuleSetEvaluation(config.amieRules, true, semanticGm, allKnownFacts, trainKnownFacts, config.test, N);

        } catch (Exception e) {
            e.printStackTrace();
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
        // Assuming your Metrics class prints Hits@1, Hits@5, Hits@10, MRR, and Sem@10
        System.out.printf("%-15s | %-10s | %-10s | %-10s | %-10s | %-10s%n",
                "Engine", "Hits@1", "Hits@5", "Hits@10", "MRR", "Sem@10");
        System.out.println("----------------------------------------------------------------------------");

        // Use printRow from Metrics (adjust strings to fit your column widths if necessary)
        standardMetrics.printRow("Standard");
        semanticMetrics.printRow("Semantic");
    }
}
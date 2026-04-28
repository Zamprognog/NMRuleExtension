package ruleMiningSemanticExtension;

import ruleMiningSemanticExtension.graphTools.SemanticGraphManager;
import ruleMiningSemanticExtension.groundingEngine.GroundingEngine;
import ruleMiningSemanticExtension.groundingEngine.RuleRegistry;
import ruleMiningSemanticExtension.evaluation.Evaluator;
import ruleMiningSemanticExtension.evaluation.Metrics;
import ruleMiningSemanticExtension.utils.DataLoader;
import ruleMiningSemanticExtension.utils.ExperimentConfig;

import java.util.HashSet;
import java.util.Set;

public class Main {

    public static void main(String[] args) {
        String configPath ;
        // Pass the JSON config path as an argument, or fallback to a default
        configPath = args.length > 0 ? args[0] : "data/NELL995/NELL995.json";
//        configPath = args.length > 0 ? args[0] : "data/hetionet/hetionet.json";
//        configPath = args.length > 0 ? args[0] : "data/CSKG2/CSKG2.json";
//        configPath = args.length > 0 ? args[0] : "data/YAGO4.5/YAGO4.5.json";
        int N = 30000; // Define max evaluations

        boolean isAmie = false;
        // 1. Load Known Facts for Filtered Evaluation
        try {
            ExperimentConfig config = ExperimentConfig.load(configPath);

            Set<String> allKnownFacts = new HashSet<>();
            DataLoader.loadFactsIntoSet(allKnownFacts, config.train, config.valid, config.test);
            Set<String> trainKnownFacts = new HashSet<>();
            DataLoader.loadFactsIntoSet(trainKnownFacts, config.train);

            // 2. Initialize Engine
            SemanticGraphManager semanticGm = new SemanticGraphManager();
            semanticGm.parseTriples(config.train, "\t");
            semanticGm.finalizeGraph();
            semanticGm.compileConstraints(config.schema, config.typesFile);
            semanticGm.precomputeDisjointConstraints();

            System.out.println("Ready for prediction");
//            runRuleSetEvaluation(config.anyburlRules, false, semanticGm, allKnownFacts, trainKnownFacts, config.test, N);

            RuleRegistry registry = new RuleRegistry();
            registry.loadRulesFromFile(config.anyburlRules, isAmie);
//            SemanticGroundingEngine engine = new SemanticGroundingEngine(semanticGm);
            GroundingEngine engine = new GroundingEngine(semanticGm);
            Evaluator evaluator = new Evaluator(engine, registry, allKnownFacts, trainKnownFacts, semanticGm);
            String logPath = config.predictionsDir != null ? config.predictionsDir + "/latest_predictions.txt" : null;
            Metrics metrics = evaluator.evaluate(config.test, logPath, N);
            metrics.printRow("Standard");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
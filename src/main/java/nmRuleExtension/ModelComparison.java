package nmRuleExtension;

import nmRuleExtension.groundingEngine.GroundingEngine;
import nmRuleExtension.groundingEngine.RuleRegistry;
import nmRuleExtension.graphTools.SemanticGraphManager;
import nmRuleExtension.groundingEngine.SemanticGroundingEngine;
import nmRuleExtension.evaluation.Evaluator;
import nmRuleExtension.evaluation.Metrics;
import nmRuleExtension.utils.DataLoader;

import java.util.HashSet;
import java.util.Set;

public class ModelComparison {

    public static void main(String[] args) {
        int N = 20000;

        // --- File Paths ---
        String graphPath     = "data/NELL995/data/NELL995_train.tsv";
        String validPath     = "data/NELL995/data/NELL995_valid.tsv";
        String testPath      = "data/NELL995/data/NELL995_test.tsv";
//        String rulesPath     = "data/NELL995/rules/NELL995_rules_amie.tsv";
        String rulesPath     = "data/NELL995/rules/NELL995_rules_all_anyburl-100";
//        String rulesPath     = "data/NELL995/rules/NELL995_rules_anyburl-1000";
        String ontologyPath  = "data/NELL995/data/NELL.ontology.ttl";
        String entityTypesPath = "data/NELL995/data/NELL995_entity_types.nt";
        Boolean isAmie = false;
        // 1. Load Known Facts for Filtered Evaluation
        Set<String> allKnownFacts = new HashSet<>();
        DataLoader.loadFactsIntoSet(allKnownFacts, graphPath, validPath, testPath);
        Set<String> trainKnownFacts = new HashSet<>();
        DataLoader.loadFactsIntoSet(trainKnownFacts, graphPath);
        System.out.println("Loaded " + allKnownFacts.size() + " known facts for filtered metrics.");
        System.out.println("Loaded " + trainKnownFacts.size() + " training facts for consistency checks.\n");

        // 2. Setup Standard Engine
        System.out.println("Initializing Standard Engine...");
//        GraphManager standardGm = new GraphManager();
//        standardGm.parseTriples(graphPath, "\t");
//        standardGm.finalizeGraph();
        SemanticGraphManager semanticGm = new SemanticGraphManager();
        semanticGm.parseTriples(graphPath, "\t");
        semanticGm.finalizeGraph();
        semanticGm.compileConstraints(ontologyPath, entityTypesPath);
        semanticGm.precomputeDisjointConstraints();
        RuleRegistry standardRegistry = new RuleRegistry();
        standardRegistry.loadRulesFromFile(rulesPath, isAmie);
        GroundingEngine standardEngine = new GroundingEngine(semanticGm);

        Evaluator standardEvaluator = new Evaluator(standardEngine, standardRegistry, allKnownFacts, trainKnownFacts, semanticGm);

        // 3. Set up Semantic Engine
        System.out.println("Initializing Semantic Engine...");
//        SemanticGraphManager semanticGm = new SemanticGraphManager();
//        semanticGm.parseTriples(graphPath, "\t");
//        semanticGm.finalizeGraph();
//        semanticGm.compileConstraints(ontologyPath, entityTypesPath);
        RuleRegistry semanticRegistry = new RuleRegistry();
        semanticRegistry.loadRulesFromFile(rulesPath, isAmie);
        SemanticGroundingEngine semanticEngine = new SemanticGroundingEngine(semanticGm);

        Evaluator semanticEvaluator = new Evaluator(semanticEngine, semanticRegistry, allKnownFacts, trainKnownFacts, semanticGm);

        // 4. Evaluate Models
        System.out.println("\n==================================================");
        System.out.println("Running Standard Link Prediction...");
        System.out.println("==================================================");
        Metrics standardMetrics = standardEvaluator.evaluate(testPath, N);

        System.out.println("\n==================================================");
        System.out.println("Running Semantic Link Prediction...");
        System.out.println("==================================================");
        Metrics semanticMetrics = semanticEvaluator.evaluate(testPath, N);

        // 5. Print Comparison Table
        System.out.println("\n==================================================");
        System.out.println("                FINAL COMPARISON                  ");
        System.out.println("==================================================");
        System.out.printf("%-15s | %-10s | %-10s | %-10s | %-10s%n", "Model", "Hits@1", "Hits@5", "Hits@10", "MRR");
        System.out.println("------------------------------------------------------------------");
        standardMetrics.printRow("Standard");
        semanticMetrics.printRow("Semantic");
    }
}
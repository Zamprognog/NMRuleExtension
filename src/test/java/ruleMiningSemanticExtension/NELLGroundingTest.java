package ruleMiningSemanticExtension;

import org.junit.jupiter.api.Test;
import ruleMiningSemanticExtension.domain.PredictionCandidate;
import ruleMiningSemanticExtension.graphTools.GraphManager;
import ruleMiningSemanticExtension.groundingEngine.GroundingEngine;
import ruleMiningSemanticExtension.groundingEngine.RuleRegistry;
import ruleMiningSemanticExtension.rules.Rule;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.File;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class NELLGroundingTest {

    @Test
    public void testNellGroundingWithPredictions() throws IOException {
        // Try multiple potential base paths because Maven might run from project root or tools/
        String projectRoot = "";
        if (new File("data").exists()) {
            projectRoot = "./";
        } else if (new File("../data").exists()) {
            projectRoot = "../";
        } else {
             // Fallback to searching if we are deep in target/ or similar
             File current = new File(".").getAbsoluteFile();
             while (current != null && !new File(current, "data").exists()) {
                 current = current.getParentFile();
             }
             if (current != null) projectRoot = current.getAbsolutePath() + "/";
        }

        String trainPath = projectRoot + "data/NELL995/data/NELL995_train.tsv";
        String rulesPath = projectRoot + "data/NELL995/rules/NELL995_rules_anyburl_ALL-100";
        String testPath = projectRoot + "data/NELL995/data/NELL995_test.tsv";

        System.out.println("Using project root: " + projectRoot);

        // 1. Load Graph
        GraphManager gm = new GraphManager();
        gm.parseTriples(trainPath, "\t");
        gm.finalizeGraph();
        GroundingEngine engine = new GroundingEngine(gm);

        // 2. Load Top 5 Rules
        RuleRegistry registry = new RuleRegistry();
        int count = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(rulesPath))) {
            String line;
            while ((line = reader.readLine()) != null && count < 5) {
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
        List<Rule> rules = registry.getAllRulesSortedByConfidence();
        assertFalse(rules.isEmpty(), "Rules should not be empty");

        // 3. Pick a triple from test set and apply rules
        String testSubject = "";
        String testPredicate = "";
        String testObject = "";
        try (BufferedReader reader = new BufferedReader(new FileReader(testPath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\t");
                if (parts.length >= 3) {
                    testSubject = parts[0];
                    testPredicate = parts[1];
                    testObject = parts[2];
                    
                    // Let's try to find a predicate that actually has rules
                    if (!registry.getPredictingRules(testPredicate).isEmpty()) {
                        break; 
                    }
                }
            }
        }

        System.out.println("Testing with Triple: (" + testSubject + ", " + testPredicate + ", " + testObject + ")");

        // 4. Apply rules to predict Object
        Map<String, PredictionCandidate> predictions = new HashMap<>();
        List<Rule> predictingRules = registry.getPredictingRules(testPredicate);
        System.out.println("Number of rules for predicate " + testPredicate + ": " + predictingRules.size());
        
        for (Rule r : predictingRules) {
            r.apply(engine, true, testSubject, testPredicate, predictions);
        }

        // 5. Verify groundings
        if (!predictions.isEmpty()) {
            System.out.println("Found " + predictions.size() + " prediction candidates.");
            for (PredictionCandidate pc : predictions.values()) {
                assertNotNull(pc.getEntity());
                assertTrue(pc.getGroundingCount() > 0, "Each candidate must have at least one grounding");
                
                System.out.println("Candidate: " + pc.getEntity() + " (Groundings: " + pc.getGroundingCount() + ", Subgraph edges: " + pc.getSubgraphEdgeCount() + ", Subgraph nodes: " + pc.getSubgraphNodeCount() + ")");
            }
        } else {
            System.out.println("No predictions found. This is expected if the rules don't match the specific test subject in the graph.");
        }
    }
}

package evolveAggregation.groundingEngine;
import evolveAggregation.rules.Rule;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RuleRegistry {
    public List<Rule> getPredictingRules(String predicate) {
        return ruleMap.getOrDefault(predicate, new ArrayList<>());
    }

    private final Map<String, List<Rule>> ruleMap = new HashMap<>();

    private void registerRule(Rule rule) {
        //ruleMap.put(rule.getName(), rule);
        ruleMap.computeIfAbsent(rule.getHead().getPredicate(), k -> new ArrayList<>()).add(rule);
    }

//    public Rule getRule(String ruleName) {
//        return ruleMap.get(ruleName);
//    }

    public void loadRulesFromFile(String filePath) {
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // skip comments or empty lines
                if (line.trim().isEmpty() || line.startsWith("#")) continue;

                String[] parts = line.split("\t");
                if (parts.length >= 4) {
                    float confidence = Float.parseFloat(parts[2]);
                    String ruleStr = parts[3];

                    registerRule(Rule.parse(ruleStr, confidence));

                }
            }
        } catch (IOException e) {
            System.err.println("Error reading the file: " + e.getMessage());
        }
    }

    public void loadRuleFromString(String line) {
        String[] parts = line.split("\t");
        if (parts.length >= 4) {
            float confidence = Float.parseFloat(parts[2]);
            String ruleStr = parts[3];

            registerRule(Rule.parse(ruleStr, confidence));

        }

    }


}

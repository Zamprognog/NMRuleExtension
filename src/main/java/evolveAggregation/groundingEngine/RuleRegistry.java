package evolveAggregation.groundingEngine;
import evolveAggregation.rules.Rule;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages the registration and retrieval of logical rules.
 * Rules are indexed by their head predicate to allow efficient lookup during
 * rule grounding and prediction tasks.
 */
public class RuleRegistry {
    /** Maps head predicates to the list of rules that predict them. */
    private final Map<String, List<Rule>> ruleMap = new HashMap<>();

    /**
     * Internal method to add a rule to the registry.
     *
     * @param rule The {@link Rule} instance to be registered.
     */
    private void registerRule(Rule rule) {
        //ruleMap.put(rule.getName(), rule);
        ruleMap.computeIfAbsent(rule.getHead().getPredicate(), k -> new ArrayList<>()).add(rule);
    }

    /**
     * Loads rules from a tab-separated file.
     * Each line is expected to have at least four parts: [ignored], [ignored], confidence, rule string.
     * Supports both AnyBURL (default) and AMIE rule formats.
     *
     * @param filePath The path to the rules file.
     * @param isAmie {@code true} if the file uses AMIE format, {@code false} for AnyBURL.
     */
    public void loadRulesFromFile(String filePath, boolean isAmie) {
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // skip comments or empty lines
                if (line.trim().isEmpty() || line.startsWith("#")) continue;

                String[] parts = line.split("\t");
                if (parts.length >= 4) {
//                    float confidence = Float.parseFloat(parts[2]) + 5;
                    float confidence = (Float.parseFloat(parts[1]) + 5 ) / Float.parseFloat(parts[0]); //offset by a lambda value to account for 'unseen data'.
                    if (confidence >= 0.99) continue; // too low predicting power
                    String ruleStr = parts[3];
                    if (isAmie) {
                        registerRule(Rule.parseAmie(ruleStr, confidence));
                    } else {
                        registerRule(Rule.parse(ruleStr, confidence));
                    }

                }
            }
            sortRulesByConfidence();
        } catch (IOException e) {
            System.err.println("Error reading the file: " + e.getMessage());
        }
    }

    /**
     * Parses and registers a single rule from a tab-separated string. - Just used for debugging.
     *
     * @param line A single line containing rule information (formatted as tab-separated).
     */
    public void loadRuleFromString(String line) {
        String[] parts = line.split("\t");
        if (parts.length >= 4) {
            float confidence = Float.parseFloat(parts[2]);
            String ruleStr = parts[3];

            registerRule(Rule.parse(ruleStr, confidence));

        }

    }

    /**
     * Sorts all registered rules by confidence in descending order (highest first).
     */
    public void sortRulesByConfidence() {
        for (List<Rule> rules : ruleMap.values()) {
            rules.sort((r1, r2) -> Float.compare(r2.getConfidence(), r1.getConfidence()));
        }
    }

    /**
     * Retrieves all rules that predict the specified predicate.
     *
     * @param predicate The head predicate for which to find rules.
     * @return A list of {@link Rule} objects predicting the predicate, or an empty list if none are found.
     */
    public List<Rule> getPredictingRules(String predicate) {
        return ruleMap.getOrDefault(predicate, new ArrayList<>());
    }

    /**
     * Retrieves all loaded rules across all relations, sorted by confidence descending.
     */
    public List<Rule> getAllRulesSortedByConfidence() {
        List<Rule> allRules = new ArrayList<>();
        for (List<Rule> rules : ruleMap.values()) {
            allRules.addAll(rules);
        }
        // Sort descending: highest confidence first
        allRules.sort((r1, r2) -> Float.compare(r2.getConfidence(), r1.getConfidence()));
        return allRules;
    }
}

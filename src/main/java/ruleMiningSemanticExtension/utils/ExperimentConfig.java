package ruleMiningSemanticExtension.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

public class ExperimentConfig {
    public String datasetName, graph, train, valid, test, testDebug;
    public String schema, typesFile, anyburlRules, anyburlRulesCP, amieRules, amieRulesCP, predictionsDir, defUri, materializeRules, groundingType;
    /** Aggregator to evolve: "hybrid" (default), "linear", "symbolic" */
    public String aggregatorType;
    /** Base random seed for evolution; run i uses seed+i. Default 42. */
    public long seed;
    /** Number of independent evolution runs. Default 1. */
    public int numRuns;
    /** Fraction of the validation set used for GP training. Default 0.7. */
    public double validationFraction;

    public static ExperimentConfig load(String jsonPath) throws IOException {
        String content = new String(Files.readAllBytes(Paths.get(jsonPath)));
        Map<String, String> map = new HashMap<>();

        // A lightweight parser for flat JSON (key-value strings)
        String[] lines = content.replace("{", "").replace("}", "").split(",");
        for (String line : lines) {
            String[] parts = line.split(":", 2);
            if (parts.length == 2) {
                String key = parts[0].replaceAll("[\"\\s]+", "").trim();
                String value = parts[1].replaceAll("\"", "").trim();
                map.put(key, value);
            }
        }

        ExperimentConfig config = new ExperimentConfig();
        config.datasetName = map.get("dataset_name");
        config.graph = map.get("graph");
        config.train = map.get("train");
        config.valid = map.get("valid");
        config.test = map.get("test");
        config.testDebug = map.get("test_debug");
        config.schema = map.get("schema");
        config.typesFile = map.get("types_file");
        config.anyburlRules = map.get("anyburl_rules");
        config.anyburlRulesCP = map.get("anyburl_rules_CP");
        config.amieRules = map.get("amie_rules");
        config.amieRulesCP = map.get("amie_rules_CP");
        config.predictionsDir = map.get("predictions_dir");
        config.defUri = map.get("def_uri");
        config.materializeRules = map.get("materialize_rules");
        config.groundingType = map.get("grounding_type");
        config.aggregatorType = map.getOrDefault("aggregator_type", "hybrid");
        config.seed = Long.parseLong(map.getOrDefault("seed", "42"));
        config.numRuns = Integer.parseInt(map.getOrDefault("num_runs", "1"));
        config.validationFraction = Double.parseDouble(map.getOrDefault("validation_fraction", "0.7"));

        return config;
    }
}
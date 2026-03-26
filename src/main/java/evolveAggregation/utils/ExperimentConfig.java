package evolveAggregation.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

public class ExperimentConfig {
    public String datasetName, graph, train, valid, test, testDebug;
    public String schema, typesFile, anyburlRules, amieRules, predictionsDir, defUri;

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
        config.amieRules = map.get("amie_rules");
        config.predictionsDir = map.get("predictions_dir");
        config.defUri = map.get("def_uri");

        return config;
    }
}
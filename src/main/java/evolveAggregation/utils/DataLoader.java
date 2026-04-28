package evolveAggregation.utils;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.Set;

public class DataLoader {

    public static void loadFactsIntoSet(Set<String> facts, String... paths) {
        for (String path : paths) {
            if (path == null || path.isEmpty()) continue;
            try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 3) {
                        facts.add(parts[0] + "\t" + parts[1] + "\t" + parts[2]);
                    }
                }
            } catch (IOException e) {
                System.err.println("Note: Could not load facts from " + path + " (File might not exist)");
            }
        }
    }
}
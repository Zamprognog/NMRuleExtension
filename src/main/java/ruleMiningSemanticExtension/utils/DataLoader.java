package ruleMiningSemanticExtension.utils;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.Set;
import ruleMiningSemanticExtension.graphTools.GraphDictionary;

public class DataLoader {

    /**
     * Encodes a triple as a single long using 21 bits per component (supports up to ~2M IDs each).
     * Returns Long.MIN_VALUE if any component ID is -1 (unknown).
     */
    public static long encodeTriple(int s, int r, int o) {
        return ((long) s << 42) | ((long) r << 21) | (long) o;
    }

    public static void loadFactsAsIds(Set<Long> facts, GraphDictionary entityDict, GraphDictionary relDict, String... paths) {
        for (String path : paths) {
            if (path == null || path.isEmpty()) continue;
            try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split("\\s+");
                    if (parts.length < 3) continue;
                    int s = entityDict.lookup(parts[0]);
                    int r = relDict.lookup(parts[1]);
                    int o = entityDict.lookup(parts[2]);
                    if (s != -1 && r != -1 && o != -1) {
                        facts.add(encodeTriple(s, r, o));
                    }
                }
            } catch (IOException e) {
                System.err.println("Note: Could not load facts from " + path + " (File might not exist)");
            }
        }
    }

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
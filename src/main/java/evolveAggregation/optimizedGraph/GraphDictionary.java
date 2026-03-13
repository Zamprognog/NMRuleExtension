package evolveAggregation.optimizedGraph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GraphDictionary {
    private final Map<String, Integer> stringToInt = new HashMap<>();
    private final List<String> intToString = new ArrayList<>();

    public int getId(String str) {
        // If it exists, return the ID. If not, assign a new one.
        return stringToInt.computeIfAbsent(str, k -> {
            int newId = intToString.size();
            intToString.add(k);
            return newId;
        });
    }

    // Returns -1 if the string doesn't exist (useful for lookups during prediction)
    public int lookup(String str) {
        return stringToInt.getOrDefault(str, -1);
    }

    public String getString(int id) {
        return intToString.get(id);
    }

    public int size() {
        return intToString.size();
    }
}

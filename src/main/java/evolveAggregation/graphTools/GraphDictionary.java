package evolveAggregation.graphTools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bi-directional mapping between String values and their corresponding integer IDs.
 * Used for memory optimization in Graph representation by replacing strings (entities/relations) with integers.
 */
public class GraphDictionary {
    /** Mapping from String to its unique integer ID. */
    private final Map<String, Integer> stringToInt = new HashMap<>();
    /** Mapping from integer ID to its corresponding String value, indexed by the ID. */
    private final List<String> intToString = new ArrayList<>();

    /**
     * Gets the ID for a given string. If the string is not in the dictionary, a new ID is assigned.
     * @param str The string to look up or add.
     * @return The unique integer ID for the string.
     */
    public int getId(String str) {
        // If it exists, return the ID. If not, assign a new one.
        return stringToInt.computeIfAbsent(str, k -> {
            int newId = intToString.size();
            intToString.add(k);
            return newId;
        });
    }

    /**
     * Looks up the ID for a given string without adding it if it's missing.
     * @param str The string to look up.
     * @return The integer ID for the string, or -1 if not found.
     */
    public int lookup(String str) {
        return stringToInt.getOrDefault(str, -1);
    }

    /**
     * Retrieves the original string associated with the given ID.
     * @param id The integer ID of the string.
     * @return The original string, or an IndexOutOfBoundsException if the ID is invalid.
     */
    public String getString(int id) {
        return intToString.get(id);
    }

    /**
     * Returns the total number of unique strings stored in the dictionary.
     * @return The size of the dictionary.
     */
    public int size() {
        return intToString.size();
    }
}

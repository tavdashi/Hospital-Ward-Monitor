import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SimpleJSON
 * ----------
 * Zero-dependency JSON parser for flat key-value objects.
 * Only handles the Arduino's output format:
 *   {"key":"value", "key2":number, "key3":boolean}
 */
public class SimpleJSON {

    /**
     * Parses a flat JSON object into a String→String map.
     * Numbers and booleans are returned as their string representations.
     */
    public static Map<String, String> parse(String json) {
        Map<String, String> result = new LinkedHashMap<>();

        // Strip surrounding braces
        json = json.trim();
        if (json.startsWith("{")) json = json.substring(1);
        if (json.endsWith("}"))   json = json.substring(0, json.length() - 1);
        json = json.trim();

        if (json.isEmpty()) return result;

        // Tokenize respecting quoted strings
        int i = 0;
        while (i < json.length()) {
            // Skip whitespace
            while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
            if (i >= json.length()) break;

            // Read key (always quoted)
            if (json.charAt(i) != '"') { i++; continue; }
            int keyStart = i + 1;
            int keyEnd   = json.indexOf('"', keyStart);
            if (keyEnd < 0) break;
            String key = json.substring(keyStart, keyEnd);
            i = keyEnd + 1;

            // Skip whitespace + colon
            while (i < json.length() && (Character.isWhitespace(json.charAt(i)) || json.charAt(i) == ':')) i++;
            if (i >= json.length()) break;

            // Read value
            String value;
            if (json.charAt(i) == '"') {
                // Quoted string
                int valStart = i + 1;
                int valEnd   = json.indexOf('"', valStart);
                if (valEnd < 0) break;
                value = json.substring(valStart, valEnd);
                i = valEnd + 1;
            } else {
                // Unquoted (number / boolean / null)
                int valStart = i;
                while (i < json.length() && json.charAt(i) != ',' && json.charAt(i) != '}') i++;
                value = json.substring(valStart, i).trim();
            }

            result.put(key, value);

            // Skip comma
            while (i < json.length() && (json.charAt(i) == ',' || Character.isWhitespace(json.charAt(i)))) i++;
        }

        return result;
    }
}

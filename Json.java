package medtour;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small hand-written JSON helper. We avoid pulling in an external JSON library (like Gson/Jackson)
 * so the whole backend can be compiled with plain `javac` — no Maven, no internet access needed,
 * only the MySQL driver jar (see README).
 *
 * write(Object) serializes Maps (as JSON objects), Lists (as JSON arrays), Strings, Numbers,
 * Booleans, and null. Anything else (LocalDate, LocalDateTime, enums, ...) is serialized via its
 * toString(). Build response bodies as LinkedHashMap<String,Object> / List<Object> and pass them
 * straight to write() — this keeps field order stable and matches the flat/nested shapes the
 * frontend already expects.
 *
 * parseFlatObject(String) parses a JSON object whose values are all scalars (no nested
 * objects/arrays) into a String map — enough for every request body this API accepts (they're all
 * flat DTOs; see README).
 */
public class Json {

    /** Escapes a string so it is safe to place inside a JSON string literal. */
    public static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    /** General-purpose serializer: Map -> object, List -> array, everything else -> scalar. */
    @SuppressWarnings("unchecked")
    public static String write(Object val) {
        if (val == null) return "null";
        if (val instanceof String) return "\"" + escape((String) val) + "\"";
        if (val instanceof Boolean || val instanceof Number) return val.toString();
        if (val instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) val;
            StringBuilder out = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> e : map.entrySet()) {
                if (!first) out.append(",");
                first = false;
                out.append("\"").append(escape(e.getKey())).append("\":").append(write(e.getValue()));
            }
            out.append("}");
            return out.toString();
        }
        if (val instanceof List) {
            List<Object> list = (List<Object>) val;
            StringBuilder out = new StringBuilder("[");
            boolean first = true;
            for (Object item : list) {
                if (!first) out.append(",");
                first = false;
                out.append(write(item));
            }
            out.append("]");
            return out.toString();
        }
        // LocalDate / LocalDateTime / enums / anything else printable
        return "\"" + escape(val.toString()) + "\"";
    }

    public static String object(Map<String, Object> fields) {
        return write(fields);
    }

    public static String array(List<Object> items) {
        return write(items);
    }

    public static String errorObject(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", 0);
        m.put("message", message);
        m.put("fieldErrors", null);
        return write(m);
    }

    /** Builds the standard { status, message, fieldErrors } error shape the frontend expects. */
    public static String apiError(int status, String message, Map<String, String> fieldErrors) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        m.put("message", message);
        m.put("fieldErrors", fieldErrors);
        return write(m);
    }

    /**
     * Parses a FLAT JSON object (no nested objects/arrays) into a String map.
     * Every request DTO this API accepts is flat, so this is all parsing we need.
     * Example input: {"patientName":"John Doe","treatmentId":3,"message":null}
     */
    public static Map<String, String> parseFlatObject(String json) {
        Map<String, String> map = new LinkedHashMap<>();
        if (json == null) return map;
        json = json.trim();
        if (json.startsWith("{")) json = json.substring(1);
        if (json.endsWith("}")) json = json.substring(0, json.length() - 1);

        int i = 0;
        int len = json.length();
        while (i < len) {
            while (i < len && (Character.isWhitespace(json.charAt(i)) || json.charAt(i) == ',')) i++;
            if (i >= len) break;

            if (json.charAt(i) != '"') break;
            i++;
            StringBuilder key = new StringBuilder();
            while (i < len && json.charAt(i) != '"') {
                if (json.charAt(i) == '\\' && i + 1 < len) { i++; }
                key.append(json.charAt(i));
                i++;
            }
            i++; // closing quote

            while (i < len && (Character.isWhitespace(json.charAt(i)) || json.charAt(i) == ':')) i++;

            String value;
            if (i < len && json.charAt(i) == '"') {
                i++;
                StringBuilder val = new StringBuilder();
                while (i < len && json.charAt(i) != '"') {
                    if (json.charAt(i) == '\\' && i + 1 < len) {
                        i++;
                        char esc = json.charAt(i);
                        switch (esc) {
                            case 'n': val.append('\n'); break;
                            case 't': val.append('\t'); break;
                            case 'r': val.append('\r'); break;
                            case 'u':
                                if (i + 4 < len) {
                                    String hex = json.substring(i + 1, i + 5);
                                    try {
                                        val.append((char) Integer.parseInt(hex, 16));
                                        i += 4;
                                    } catch (NumberFormatException nfe) {
                                        val.append(esc);
                                    }
                                } else {
                                    val.append(esc);
                                }
                                break;
                            default: val.append(esc);
                        }
                    } else {
                        val.append(json.charAt(i));
                    }
                    i++;
                }
                i++; // closing quote
                value = val.toString();
            } else if (i < len && (json.charAt(i) == '{' || json.charAt(i) == '[')) {
                // Skip a nested object/array value we don't support parsing — find its matching bracket.
                char open = json.charAt(i);
                char close = open == '{' ? '}' : ']';
                int depth = 0;
                int start = i;
                do {
                    if (json.charAt(i) == open) depth++;
                    else if (json.charAt(i) == close) depth--;
                    i++;
                } while (i < len && depth > 0);
                value = json.substring(start, i);
            } else {
                int start = i;
                while (i < len && json.charAt(i) != ',' && json.charAt(i) != '}') i++;
                value = json.substring(start, i).trim();
                if (value.equals("null")) value = null;
            }
            map.put(key.toString(), value);
        }
        return map;
    }
}

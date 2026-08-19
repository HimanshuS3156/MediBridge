package medtour;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Shared helpers for reading requests and writing JSON responses with CORS enabled. */
public class Http {

    /** Adds CORS headers. Call at the start of every handler. Returns true if this was a
     *  pre-flight OPTIONS request that has already been fully handled (caller should return). */
    public static boolean handleCors(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, Authorization");
        if (ex.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            ex.sendResponseHeaders(204, -1);
            return true;
        }
        return false;
    }

    public static void sendJson(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** Serializes any Java object (Map/List/scalar) as the response body. */
    public static void sendObject(HttpExchange ex, int status, Object body) throws IOException {
        sendJson(ex, status, Json.write(body));
    }

    public static void sendError(HttpExchange ex, int status, String message) throws IOException {
        sendJson(ex, status, Json.apiError(status, message, null));
    }

    /** Turns an ApiException (or any Throwable) into the standard { status, message, fieldErrors }
     *  JSON error shape — the plain-Java equivalent of GlobalExceptionHandler. Call this from the
     *  catch block of every handler's handle() method. */
    public static void sendApiError(HttpExchange ex, Throwable t) throws IOException {
        if (t instanceof ApiException apiEx) {
            sendJson(ex, apiEx.getStatus(), Json.apiError(apiEx.getStatus(), apiEx.getMessage(), apiEx.getFieldErrors()));
        } else {
            System.err.println("Unhandled exception: " + t);
            t.printStackTrace();
            sendJson(ex, 500, Json.apiError(500, "Something went wrong on our end. Please try again.", null));
        }
    }

    public static String readBody(HttpExchange ex) throws IOException {
        InputStream is = ex.getRequestBody();
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        return bos.toString(StandardCharsets.UTF_8.name());
    }

    /** Parses query string like "hospitalId=2&category=Dental" into a map. */
    public static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isEmpty()) return map;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String key = java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            String val = java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            map.put(key, val);
        }
        return map;
    }

    /** Splits a request path like "/api/appointments/42/reschedule" into segments, ignoring the
     *  given number of leading segments (the context path Main.java registered this handler under). */
    public static List<String> pathSegmentsAfter(HttpExchange ex, String contextPath) {
        String path = ex.getRequestURI().getPath();
        String rest = path.substring(contextPath.length());
        while (rest.startsWith("/")) rest = rest.substring(1);
        if (rest.isEmpty()) return List.of();
        return List.of(rest.split("/"));
    }

    public static Integer parseIntOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static Integer requirePathInt(String s, String label) {
        Integer v = parseIntOrNull(s);
        if (v == null) throw new ApiException(400, "Invalid value for '" + label + "'.");
        return v;
    }
}

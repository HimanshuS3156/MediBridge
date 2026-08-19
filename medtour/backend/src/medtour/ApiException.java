package medtour;

import java.util.Map;

/**
 * Thrown for expected, user-facing failures (bad input, conflicts, not found, unauthorized).
 * Every handler catches this at the top level and turns it into the same JSON error shape
 * the frontend expects: { status, message, fieldErrors }.
 */
public class ApiException extends RuntimeException {
    private final int status;
    private final Map<String, String> fieldErrors;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
        this.fieldErrors = null;
    }

    public ApiException(int status, String message, Map<String, String> fieldErrors) {
        super(message);
        this.status = status;
        this.fieldErrors = fieldErrors;
    }

    public int getStatus() { return status; }
    public Map<String, String> getFieldErrors() { return fieldErrors; }

    /** 400 with a single field's validation message — matches the frontend's applyBackendFieldErrors(). */
    public static ApiException validation(String field, String message) {
        return new ApiException(400, "Please fix the highlighted fields.", Map.of(field, message));
    }

    public static ApiException validation(Map<String, String> fieldErrors) {
        return new ApiException(400, "Please fix the highlighted fields.", fieldErrors);
    }
}

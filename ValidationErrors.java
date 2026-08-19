package medtour;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Collects field-level validation errors so a whole form can be checked and every problem
 * reported back at once — matching how Spring's @Valid + Bean Validation surfaced every
 * violation on a DTO in a single { fieldErrors: {...} } response, which js/api.js's
 * applyBackendFieldErrors() already knows how to paint onto the right inputs.
 */
public class ValidationErrors {

    private final Map<String, String> errors = new LinkedHashMap<>();

    public void require(String value, String field, String message) {
        if (value == null || value.trim().isEmpty()) {
            errors.putIfAbsent(field, message);
        }
    }

    public void maxLength(String value, int max, String field, String message) {
        if (value != null && value.length() > max) {
            errors.putIfAbsent(field, message);
        }
    }

    public void email(String value, String field) {
        require(value, field, "Email is required.");
        if (value != null && !value.isBlank() && !errors.containsKey(field)
                && !value.trim().matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")) {
            errors.put(field, "Email must include a username, @, and a valid domain (e.g. name@example.com).");
        }
    }

    public void phone(String value, String field) {
        require(value, field, "Phone number is required.");
        if (value != null && !value.isBlank() && !errors.containsKey(field)
                && !value.trim().matches("^\\+[1-9]\\d{6,14}$")) {
            errors.put(field, "Phone must be in international format, e.g. +919876543210.");
        }
    }

    public void password(String value, String field) {
        require(value, field, "Password is required.");
        if (value != null && !errors.containsKey(field)
                && (value.length() < 8 || value.length() > 100 || !value.matches("^(?=.*[A-Za-z])(?=.*\\d).+$"))) {
            errors.put(field, "Password must be at least 8 characters and include at least one letter and one number.");
        }
    }

    public void requiredInt(Integer value, String field, String message) {
        if (value == null) errors.putIfAbsent(field, message);
    }

    public void range(Integer value, int min, int max, String field, String message) {
        if (value != null && (value < min || value > max)) {
            errors.putIfAbsent(field, message);
        }
    }

    public void custom(boolean invalid, String field, String message) {
        if (invalid) errors.putIfAbsent(field, message);
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public void throwIfInvalid() {
        if (hasErrors()) {
            throw ApiException.validation(errors);
        }
    }
}

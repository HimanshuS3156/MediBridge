package medtour;

import java.util.regex.Pattern;

/**
 * Shared validation helpers mirroring the Bean Validation annotations the Spring Boot DTOs used
 * (@NotBlank, @Email, @Pattern, @Size, ...). Each check throws ApiException.validation(field, msg)
 * on failure, matching the { status:400, message:"Please fix the highlighted fields.",
 * fieldErrors:{field: msg} } shape js/api.js already knows how to render onto form fields.
 */
public class Validate {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\+[1-9]\\d{6,14}$");
    private static final Pattern PASSWORD_LETTER_DIGIT = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d).+$");

    private Validate() {}

    public static String notBlank(String value, String field, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw ApiException.validation(field, message);
        }
        return value;
    }

    public static String maxLength(String value, int max, String field, String message) {
        if (value != null && value.length() > max) {
            throw ApiException.validation(field, message);
        }
        return value;
    }

    public static String email(String value, String field) {
        notBlank(value, field, "Email is required.");
        if (!EMAIL_PATTERN.matcher(value.trim()).matches()) {
            throw ApiException.validation(field, "Email must include a username, @, and a valid domain (e.g. name@example.com).");
        }
        return value;
    }

    public static String phone(String value, String field) {
        notBlank(value, field, "Phone number is required.");
        if (!PHONE_PATTERN.matcher(value.trim()).matches()) {
            throw ApiException.validation(field, "Phone must be in international format, e.g. +919876543210.");
        }
        return value;
    }

    public static String password(String value, String field) {
        notBlank(value, field, "Password is required.");
        if (value.length() < 8 || value.length() > 100 || !PASSWORD_LETTER_DIGIT.matcher(value).matches()) {
            throw ApiException.validation(field, "Password must be at least 8 characters and include at least one letter and one number.");
        }
        return value;
    }

    public static Integer requiredInt(String value, String field, String message) {
        if (value == null || value.isBlank()) {
            throw ApiException.validation(field, message);
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw ApiException.validation(field, message);
        }
    }

    public static Integer optionalInt(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static boolean parseBool(String value) {
        return "true".equalsIgnoreCase(value);
    }
}

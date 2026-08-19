package medtour;

/**
 * Strips HTML/script tags from freeform user text before it's persisted. Defense-in-depth
 * alongside the frontend's escapeHtml() at render time. Used for freeform fields only
 * (appointment notes, feedback comments, names) — never applied to structured fields like
 * email/phone, which are already constrained by their own pattern validation.
 */
public class TextSanitizer {
    private TextSanitizer() {}

    public static String stripTags(String input) {
        if (input == null) return null;
        return input.replaceAll("<[^>]*>", "");
    }
}

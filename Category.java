import java.util.Locale;

/** The fixed set of spending categories an expense can belong to. */
public enum Category {
    FOOD,
    TRANSPORTATION,
    HOUSING,
    UTILITIES,
    ENTERTAINMENT,
    HEALTH,
    EDUCATION,
    SHOPPING,
    MISC;

    /** Parses a category name case-insensitively, e.g. "food" -> FOOD. */
    public static Category parse(String value) {
        if (value == null || value.isBlank()) {
            throw new ValidationException("category is required");
        }
        try {
            return Category.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("unknown category '" + value + "'");
        }
    }
}

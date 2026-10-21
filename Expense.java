import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A single, immutable expense entry.
 *
 * Amounts use BigDecimal (scale 2) rather than double so that money is never
 * subject to floating point rounding error.
 */
public final class Expense {
    public static final int MAX_DESCRIPTION_LENGTH = 255;

    private final long id;
    private final BigDecimal amount;
    private final Category category;
    private final LocalDate date;
    private final String description;

    public Expense(long id, BigDecimal amount, Category category, LocalDate date, String description) {
        if (amount == null) {
            throw new ValidationException("amount is required");
        }
        if (amount.signum() <= 0) {
            throw new ValidationException("amount must be greater than 0");
        }
        try {
            this.amount = amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new ValidationException("amount cannot have more than 2 decimal places");
        }
        if (category == null) {
            throw new ValidationException("category is required");
        }
        if (date == null) {
            throw new ValidationException("date is required");
        }
        String desc = description == null ? "" : description.trim();
        if (desc.length() > MAX_DESCRIPTION_LENGTH) {
            throw new ValidationException("description cannot exceed " + MAX_DESCRIPTION_LENGTH + " characters");
        }
        this.id = id;
        this.category = category;
        this.date = date;
        this.description = desc;
    }

    /** Returns a copy of this expense with a different id. */
    public Expense withId(long newId) {
        return new Expense(newId, amount, category, date, description);
    }

    public long getId() {
        return id;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Category getCategory() {
        return category;
    }

    public LocalDate getDate() {
        return date;
    }

    public String getDescription() {
        return description;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Expense other)) return false;
        return id == other.id
                && amount.equals(other.amount)
                && category == other.category
                && date.equals(other.date)
                && description.equals(other.description);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, amount, category, date, description);
    }

    @Override
    public String toString() {
        return "Expense{id=" + id + ", amount=" + amount + ", category=" + category
                + ", date=" + date + ", description='" + description + "'}";
    }
}

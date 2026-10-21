import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Map;

/**
 * Aggregated spending statistics over a set of expenses.
 *
 * @param total      sum of all amounts
 * @param count      number of expenses
 * @param byCategory total per category (only categories with spending)
 * @param byMonth    total per calendar month, in chronological order
 */
public record Summary(BigDecimal total, int count,
                      Map<Category, BigDecimal> byCategory,
                      Map<YearMonth, BigDecimal> byMonth) {
}

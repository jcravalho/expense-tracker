import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Business logic for creating, querying, updating and summarising expenses.
 *
 * All public methods are synchronized so the manager is safe to share between
 * the HTTP server's worker threads. Every mutation is written through to
 * {@link FileStorage}; if the write fails, the in-memory change is rolled back
 * so memory and disk never disagree.
 */
public class ExpenseManager {
    private static final Comparator<Expense> NEWEST_FIRST =
            Comparator.comparing(Expense::getDate).reversed()
                      .thenComparing(Comparator.comparingLong(Expense::getId).reversed());

    private final FileStorage storage;
    private final Map<Long, Expense> expenses = new TreeMap<>();
    private long nextId = 1;

    public ExpenseManager(FileStorage storage) throws IOException {
        this.storage = storage;
        for (Expense e : storage.load()) {
            if (expenses.putIfAbsent(e.getId(), e) != null) {
                throw new IOException("Duplicate expense id " + e.getId() + " in " + storage.getFile());
            }
            nextId = Math.max(nextId, e.getId() + 1);
        }
    }

    public synchronized Expense add(BigDecimal amount, Category category, LocalDate date, String description) {
        Expense expense = new Expense(nextId, amount, category, date, description);
        expenses.put(expense.getId(), expense);
        try {
            persist();
        } catch (UncheckedIOException e) {
            expenses.remove(expense.getId());
            throw e;
        }
        nextId++;
        return expense;
    }

    public synchronized Expense get(long id) {
        Expense expense = expenses.get(id);
        if (expense == null) {
            throw new NotFoundException("expense " + id + " not found");
        }
        return expense;
    }

    /**
     * Returns expenses matching all supplied filters, newest first.
     * Any filter may be null to mean "no restriction". Date bounds are inclusive.
     */
    public synchronized List<Expense> find(Category category, LocalDate from, LocalDate to) {
        validateRange(from, to);
        List<Expense> result = new ArrayList<>();
        for (Expense e : expenses.values()) {
            if (matches(e, category, from, to)) {
                result.add(e);
            }
        }
        result.sort(NEWEST_FIRST);
        return result;
    }

    public synchronized Expense update(long id, BigDecimal amount, Category category, LocalDate date, String description) {
        Expense previous = get(id);
        Expense updated = new Expense(id, amount, category, date, description);
        expenses.put(id, updated);
        try {
            persist();
        } catch (UncheckedIOException e) {
            expenses.put(id, previous);
            throw e;
        }
        return updated;
    }

    public synchronized void delete(long id) {
        Expense removed = get(id);
        expenses.remove(id);
        try {
            persist();
        } catch (UncheckedIOException e) {
            expenses.put(id, removed);
            throw e;
        }
    }

    /** Totals spending overall, per category and per month within an optional date range. */
    public synchronized Summary summarize(LocalDate from, LocalDate to) {
        validateRange(from, to);
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        int count = 0;
        Map<Category, BigDecimal> byCategory = new EnumMap<>(Category.class);
        Map<YearMonth, BigDecimal> byMonth = new TreeMap<>();

        for (Expense e : expenses.values()) {
            if (!matches(e, null, from, to)) {
                continue;
            }
            total = total.add(e.getAmount());
            count++;
            byCategory.merge(e.getCategory(), e.getAmount(), BigDecimal::add);
            byMonth.merge(YearMonth.from(e.getDate()), e.getAmount(), BigDecimal::add);
        }
        return new Summary(total, count, byCategory, byMonth);
    }

    public synchronized int size() {
        return expenses.size();
    }

    private static boolean matches(Expense e, Category category, LocalDate from, LocalDate to) {
        return (category == null || e.getCategory() == category)
                && (from == null || !e.getDate().isBefore(from))
                && (to == null || !e.getDate().isAfter(to));
    }

    private static void validateRange(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new ValidationException("'from' date must not be after 'to' date");
        }
    }

    private void persist() {
        try {
            storage.save(expenses.values());
        } catch (IOException e) {
            throw new UncheckedIOException("failed to save expenses", e);
        }
    }
}

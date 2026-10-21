import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * Self-contained test suite (no JUnit needed). Run with: java ExpenseTrackerTests
 * Exits with status 1 if any test fails.
 */
public class ExpenseTrackerTests {
    private static int passed = 0;
    private static int failed = 0;

    interface TestBody {
        void run() throws Exception;
    }

    public static void main(String[] args) {
        test("rejects non-positive amounts", () ->
                expectThrows(ValidationException.class, () -> expense("0")));
        test("rejects more than 2 decimal places", () ->
                expectThrows(ValidationException.class, () -> expense("1.234")));
        test("normalises amount scale", () ->
                assertEquals(new BigDecimal("5.00"), expense("5").getAmount()));
        test("parses category case-insensitively", () ->
                assertEquals(Category.FOOD, Category.parse(" food ")));
        test("rejects unknown category", () ->
                expectThrows(ValidationException.class, () -> Category.parse("pets")));

        test("add assigns increasing ids", () -> {
            ExpenseManager m = newManager();
            Expense a = m.add(new BigDecimal("1"), Category.FOOD, LocalDate.of(2026, 1, 1), "a");
            Expense b = m.add(new BigDecimal("2"), Category.FOOD, LocalDate.of(2026, 1, 2), "b");
            assertEquals(1L, a.getId());
            assertEquals(2L, b.getId());
        });

        test("find filters by category and inclusive date range, newest first", () -> {
            ExpenseManager m = newManager();
            m.add(new BigDecimal("10"), Category.FOOD, LocalDate.of(2026, 1, 1), "jan");
            m.add(new BigDecimal("20"), Category.FOOD, LocalDate.of(2026, 2, 1), "feb");
            m.add(new BigDecimal("30"), Category.HOUSING, LocalDate.of(2026, 2, 1), "rent");
            m.add(new BigDecimal("40"), Category.FOOD, LocalDate.of(2026, 3, 1), "mar");
            List<Expense> found = m.find(Category.FOOD, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1));
            assertEquals(2, found.size());
            assertEquals("feb", found.get(0).getDescription());
            assertEquals("jan", found.get(1).getDescription());
        });

        test("find rejects from after to", () ->
                expectThrows(ValidationException.class,
                        () -> newManager().find(null, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 1))));

        test("update and delete", () -> {
            ExpenseManager m = newManager();
            Expense e = m.add(new BigDecimal("1"), Category.FOOD, LocalDate.of(2026, 1, 1), "x");
            m.update(e.getId(), new BigDecimal("9.99"), Category.HEALTH, LocalDate.of(2026, 1, 5), "y");
            assertEquals(new BigDecimal("9.99"), m.get(e.getId()).getAmount());
            m.delete(e.getId());
            expectThrows(NotFoundException.class, () -> m.get(e.getId()));
            expectThrows(NotFoundException.class, () -> m.delete(e.getId()));
        });

        test("summary totals by category and month", () -> {
            ExpenseManager m = newManager();
            m.add(new BigDecimal("10.10"), Category.FOOD, LocalDate.of(2026, 1, 1), "");
            m.add(new BigDecimal("0.20"), Category.FOOD, LocalDate.of(2026, 1, 15), "");
            m.add(new BigDecimal("100"), Category.HOUSING, LocalDate.of(2026, 2, 1), "");
            Summary s = m.summarize(null, null);
            assertEquals(new BigDecimal("110.30"), s.total());
            assertEquals(3, s.count());
            assertEquals(new BigDecimal("10.30"), s.byCategory().get(Category.FOOD));
            assertEquals(new BigDecimal("100.00"), s.byMonth().get(YearMonth.of(2026, 2)));
        });

        test("data survives a reload, including tricky descriptions", () -> {
            Path file = tempFile();
            ExpenseManager m = new ExpenseManager(new FileStorage(file));
            m.add(new BigDecimal("3.50"), Category.FOOD, LocalDate.of(2026, 1, 1), "coffee, \"large\"\nwith milk");
            m.add(new BigDecimal("2"), Category.MISC, LocalDate.of(2026, 1, 2), "plain");

            ExpenseManager reloaded = new ExpenseManager(new FileStorage(file));
            assertEquals(2, reloaded.size());
            assertEquals("coffee, \"large\"\nwith milk", reloaded.get(1).getDescription());
            Expense next = reloaded.add(new BigDecimal("1"), Category.MISC, LocalDate.of(2026, 1, 3), "");
            assertEquals(3L, next.getId());
        });

        test("corrupt data file is reported", () -> {
            Path file = tempFile();
            Files.writeString(file, "id,amount,category,date,description\n1,abc,FOOD,2026-01-01,x\n");
            expectThrows(IOException.class, () -> new ExpenseManager(new FileStorage(file)));
        });

        test("json round trip", () -> {
            Object parsed = Json.parse("{\"a\": [1, 2.50, \"x\\n\\u0041\"], \"b\": true, \"c\": null}");
            assertEquals("{\"a\":[1,2.50,\"x\\nA\"],\"b\":true,\"c\":null}", Json.write(parsed));
            expectThrows(ValidationException.class, () -> Json.parse("{\"a\": }"));
            assertEquals(Map.of(), Json.parse(" {} "));
        });

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------- helpers

    private static Expense expense(String amount) {
        return new Expense(1, new BigDecimal(amount), Category.MISC, LocalDate.of(2026, 1, 1), "");
    }

    private static ExpenseManager newManager() throws IOException {
        return new ExpenseManager(new FileStorage(tempFile()));
    }

    private static Path tempFile() throws IOException {
        Path file = Files.createTempFile("expenses-test", ".csv");
        Files.delete(file);
        file.toFile().deleteOnExit();
        return file;
    }

    private static void test(String name, TestBody body) {
        try {
            body.run();
            passed++;
            System.out.println("PASS  " + name);
        } catch (Throwable t) {
            failed++;
            System.out.println("FAIL  " + name + " -> " + t);
        }
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void expectThrows(Class<? extends Throwable> type, TestBody body) {
        try {
            body.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) {
                return;
            }
            throw new AssertionError("expected " + type.getSimpleName() + " but got " + t);
        }
        throw new AssertionError("expected " + type.getSimpleName() + " to be thrown");
    }
}

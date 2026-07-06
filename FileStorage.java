import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Persists expenses to a CSV file.
 *
 * Format: a header row followed by one row per expense:
 *   id,amount,category,date,description
 * Fields containing commas, quotes or newlines are quoted (RFC 4180 style).
 *
 * Writes go to a temporary file which then replaces the real file, so a crash
 * mid-write never leaves a half-written data file behind.
 */
public class FileStorage {
    private static final String HEADER = "id,amount,category,date,description";
    private static final int COLUMNS = 5;

    private final Path file;

    public FileStorage(Path file) {
        this.file = file;
    }

    public Path getFile() {
        return file;
    }

    /** Loads all expenses, or an empty list if the file does not exist yet. */
    public List<Expense> load() throws IOException {
        List<Expense> expenses = new ArrayList<>();
        if (!Files.exists(file)) {
            return expenses;
        }
        List<List<String>> rows = parseCsv(Files.readString(file, StandardCharsets.UTF_8));
        for (int i = 0; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (i == 0 && String.join(",", row).equals(HEADER)) {
                continue;
            }
            if (row.size() == 1 && row.get(0).isEmpty()) {
                continue; // blank line
            }
            if (row.size() != COLUMNS) {
                throw new IOException("Malformed row " + (i + 1) + " in " + file + ": expected "
                        + COLUMNS + " columns but found " + row.size());
            }
            try {
                expenses.add(new Expense(
                        Long.parseLong(row.get(0)),
                        new BigDecimal(row.get(1)),
                        Category.parse(row.get(2)),
                        LocalDate.parse(row.get(3)),
                        row.get(4)));
            } catch (RuntimeException e) {
                throw new IOException("Invalid data on row " + (i + 1) + " in " + file + ": " + e.getMessage(), e);
            }
        }
        return expenses;
    }

    /** Overwrites the file with the given expenses. */
    public void save(Collection<Expense> expenses) throws IOException {
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (Expense e : expenses) {
            sb.append(e.getId()).append(',')
              .append(e.getAmount().toPlainString()).append(',')
              .append(e.getCategory().name()).append(',')
              .append(e.getDate()).append(',')
              .append(escape(e.getDescription())).append('\n');
        }

        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path tmp = Files.createTempFile(dir, file.getFileName().toString(), ".tmp");
        try {
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    static String escape(String field) {
        if (field.contains(",") || field.contains("\"") || field.contains("\n") || field.contains("\r")) {
            return "\"" + field.replace("\"", "\"\"") + "\"";
        }
        return field;
    }

    /** Splits CSV text into rows of fields, honouring quoted fields that span lines. */
    static List<List<String>> parseCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }
}

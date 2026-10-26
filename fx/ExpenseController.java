import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.chart.PieChart;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Controller for expense-tracker.fxml.
 *
 * Talks to {@link ExpenseManager} directly, the same way {@link ExpenseServer} does,
 * so validation and saving behave exactly like the REST API.
 */
public class ExpenseController {
    private static final NumberFormat MONEY = NumberFormat.getCurrencyInstance(Locale.CANADA);

    /** One color per Category, in declaration order (FOOD first, MISC last). */
    private static final String[] CATEGORY_COLORS = {
        "#e4572e", "#29335c", "#f3a712", "#669bbc", "#a8c686",
        "#c1666b", "#6d597a", "#2a9d8f", "#9e9e9e"
    };

    @FXML private TextField amountField;
    @FXML private ComboBox<Category> categoryBox;
    @FXML private DatePicker dateField;
    @FXML private TextField descriptionField;
    @FXML private Label errorLabel;

    @FXML private TableView<Expense> expenseTable;
    @FXML private TableColumn<Expense, LocalDate> dateColumn;
    @FXML private TableColumn<Expense, Category> categoryColumn;
    @FXML private TableColumn<Expense, BigDecimal> amountColumn;
    @FXML private TableColumn<Expense, String> descriptionColumn;

    @FXML private ComboBox<Category> filterCategoryBox;
    @FXML private DatePicker fromDate;
    @FXML private DatePicker toDate;

    @FXML private PieChart categoryChart;
    @FXML private VBox legendBox;
    @FXML private Label totalLabel;

    private ExpenseManager manager;
    private Category filterCategory;
    private LocalDate filterFrom;
    private LocalDate filterTo;

    /** Called by ExpenseApp after the FXML is loaded. */
    public void setManager(ExpenseManager manager) {
        this.manager = manager;
        refresh();
    }

    @FXML
    private void initialize() {
        categoryBox.getItems().setAll(Category.values());
        categoryBox.setValue(Category.FOOD);
        filterCategoryBox.getItems().setAll(Category.values());
        dateField.setValue(LocalDate.now());

        dateColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().getDate()));
        categoryColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().getCategory()));
        amountColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().getAmount()));
        descriptionColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().getDescription()));
        amountColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(BigDecimal amount, boolean empty) {
                super.updateItem(amount, empty);
                setText(empty || amount == null ? null : MONEY.format(amount));
                setAlignment(Pos.CENTER_RIGHT);
            }
        });
        expenseTable.setPlaceholder(new Label("No expenses yet. Add one above."));
    }

    @FXML
    private void handleAdd() {
        try {
            manager.add(parseAmount(amountField.getText()), categoryBox.getValue(),
                    dateField.getValue(), descriptionField.getText());
            amountField.clear();
            descriptionField.clear();
            amountField.requestFocus();
            refresh();
        } catch (ValidationException | UncheckedIOException e) {
            showError(e.getMessage());
        }
    }

    @FXML
    private void handleDelete() {
        Expense selected = expenseTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showError("select an expense in the table first");
            return;
        }
        try {
            manager.delete(selected.getId());
            refresh();
        } catch (NotFoundException | UncheckedIOException e) {
            showError(e.getMessage());
        }
    }

    @FXML
    private void handleFilter() {
        filterCategory = filterCategoryBox.getValue();
        filterFrom = fromDate.getValue();
        filterTo = toDate.getValue();
        refresh();
    }

    @FXML
    private void handleClearFilter() {
        filterCategoryBox.setValue(null);
        fromDate.setValue(null);
        toDate.setValue(null);
        handleFilter();
    }

    /** Reloads the table, chart and total from the manager using the current filters. */
    private void refresh() {
        List<Expense> expenses;
        try {
            expenses = manager.find(filterCategory, filterFrom, filterTo);
        } catch (ValidationException e) {
            showError(e.getMessage());
            return;
        }
        expenseTable.getItems().setAll(expenses);

        BigDecimal total = BigDecimal.ZERO;
        Map<Category, BigDecimal> byCategory = new EnumMap<>(Category.class);
        for (Expense e : expenses) {
            total = total.add(e.getAmount());
            byCategory.merge(e.getCategory(), e.getAmount(), BigDecimal::add);
        }
        // Each category keeps a fixed color, and the legend is built here from the same colors.
        // (PieChart's built-in legend gets its colors out of sync when the data is replaced.)
        categoryChart.getData().clear();
        legendBox.getChildren().clear();
        byCategory.forEach((category, amount) -> {
            String color = CATEGORY_COLORS[category.ordinal()];
            PieChart.Data slice = new PieChart.Data(category.name(), amount.doubleValue());
            categoryChart.getData().add(slice);
            slice.getNode().setStyle("-fx-pie-color: " + color + ";");

            Region swatch = new Region();
            swatch.setMinSize(12, 12);
            swatch.setStyle("-fx-background-color: " + color + "; -fx-background-radius: 6;");
            HBox row = new HBox(6, swatch, new Label(category + "  " + MONEY.format(amount)));
            row.setAlignment(Pos.CENTER_LEFT);
            legendBox.getChildren().add(row);
        });
        totalLabel.setText("Total: " + MONEY.format(total) + " across " + expenses.size()
                + (expenses.size() == 1 ? " expense" : " expenses"));
        errorLabel.setText("");
    }

    private void showError(String message) {
        errorLabel.setText(message);
    }

    /** Accepts input like "12.50", "$12.50" or "1,200". */
    private static BigDecimal parseAmount(String text) {
        if (text == null || text.isBlank()) {
            throw new ValidationException("amount is required");
        }
        try {
            return new BigDecimal(text.trim().replace("$", "").replace(",", ""));
        } catch (NumberFormatException e) {
            throw new ValidationException("amount must be a number, like 12.50");
        }
    }
}

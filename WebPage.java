import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * A simple web page for the expense tracker, built entirely on the server in Java.
 *
 * There is no JavaScript: the browser just shows the HTML we send, and plain
 * HTML forms send data back. Routes:
 *   GET  /              the page (optional filters: ?category=&from=&to=)
 *   POST /add           add an expense from the form
 *   POST /delete/{id}   delete an expense
 *
 * After a POST we redirect back to "/" (the Post/Redirect/Get pattern), so
 * refreshing the page never submits the form a second time.
 */
public class WebPage {
    private static final String STYLE = """
            body { font-family: system-ui, sans-serif; max-width: 860px; margin: 0 auto; padding: 24px 16px;
                   background: #f6f7f9; color: #222; }
            h1 { margin-top: 0; }
            h2 { font-size: 1.1rem; margin: 0 0 12px; }
            .card { background: white; border: 1px solid #ddd; border-radius: 8px; padding: 16px; margin-bottom: 16px; }
            .error { background: #fde8e8; border-color: #f5b5b5; color: #8a1c1c; }
            form.row { display: flex; flex-wrap: wrap; gap: 8px; align-items: end; }
            label { display: flex; flex-direction: column; font-size: 0.85rem; gap: 4px; }
            input, select, button { font: inherit; padding: 6px 8px; border: 1px solid #bbb; border-radius: 4px; }
            button { background: #2f6fdf; color: white; border-color: #2f6fdf; cursor: pointer; }
            button.delete { background: none; color: #b42318; border: none; padding: 0; }
            table { width: 100%; border-collapse: collapse; }
            th, td { text-align: left; padding: 8px; border-bottom: 1px solid #eee; }
            td.amount, th.amount { text-align: right; }
            .totals { display: flex; flex-wrap: wrap; gap: 24px; }
            .big { font-size: 1.6rem; font-weight: bold; }
            .muted { color: #777; }
            """;

    private final ExpenseManager manager;

    public WebPage(ExpenseManager manager) {
        this.manager = manager;
    }

    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();

            if (method.equals("GET") && path.equals("/")) {
                showPage(exchange);
            } else if (method.equals("POST") && path.equals("/add")) {
                addExpense(exchange);
            } else if (method.equals("POST") && path.matches("/delete/\\d+")) {
                deleteExpense(exchange, Long.parseLong(path.substring("/delete/".length())));
            } else {
                sendHtml(exchange, 404, "<h1>Page not found</h1><p><a href=\"/\">Back to the expense tracker</a></p>");
            }
        }
    }

    // ---------------------------------------------------------------- actions

    private void addExpense(HttpExchange exchange) throws IOException {
        Map<String, String> form = ExpenseServer.parseQuery(ExpenseServer.readBody(exchange));
        try {
            BigDecimal amount;
            try {
                amount = new BigDecimal(form.getOrDefault("amount", "").trim());
            } catch (NumberFormatException e) {
                throw new ValidationException("amount must be a number");
            }
            manager.add(amount,
                    Category.parse(form.get("category")),
                    ExpenseServer.parseDate(form.get("date"), "date"),
                    form.get("description"));
            redirect(exchange, "/");
        } catch (ValidationException e) {
            redirect(exchange, "/?error=" + URLEncoder.encode(e.getMessage(), StandardCharsets.UTF_8));
        }
    }

    private void deleteExpense(HttpExchange exchange, long id) throws IOException {
        try {
            manager.delete(id);
            redirect(exchange, "/");
        } catch (NotFoundException e) {
            redirect(exchange, "/?error=" + URLEncoder.encode(e.getMessage(), StandardCharsets.UTF_8));
        }
    }

    // ---------------------------------------------------------------- the page

    private void showPage(HttpExchange exchange) throws IOException {
        Map<String, String> query = ExpenseServer.parseQuery(exchange.getRequestURI().getRawQuery());
        String error = query.get("error");
        String categoryFilter = query.getOrDefault("category", "");
        String fromFilter = query.getOrDefault("from", "");
        String toFilter = query.getOrDefault("to", "");

        List<Expense> expenses;
        try {
            expenses = manager.find(
                    categoryFilter.isBlank() ? null : Category.parse(categoryFilter),
                    ExpenseServer.parseDate(fromFilter, "from"),
                    ExpenseServer.parseDate(toFilter, "to"));
        } catch (ValidationException e) {
            // Bad filter values: show the error and fall back to showing everything.
            error = e.getMessage();
            expenses = manager.find(null, null, null);
        }

        StringBuilder html = new StringBuilder();
        if (error != null) {
            html.append("<div class=\"card error\">").append(escape(error)).append("</div>");
        }
        appendAddForm(html);
        appendTotals(html, expenses);
        appendFilterForm(html, categoryFilter, fromFilter, toFilter);
        appendTable(html, expenses);
        sendHtml(exchange, 200, html.toString());
    }

    private void appendAddForm(StringBuilder html) {
        html.append("""
                <div class="card">
                  <h2>Add an expense</h2>
                  <form class="row" method="post" action="/add">
                    <label>Amount <input name="amount" type="number" step="0.01" min="0.01" required></label>
                    <label>Category <select name="category">""");
        for (Category c : Category.values()) {
            html.append("<option value=\"").append(c.name()).append("\">").append(pretty(c)).append("</option>");
        }
        html.append("""
                    </select></label>
                    <label>Date <input name="date" type="date" required value="%s"></label>
                    <label>Description <input name="description" maxlength="%d"></label>
                    <button type="submit">Add</button>
                  </form>
                </div>""".formatted(LocalDate.now(), Expense.MAX_DESCRIPTION_LENGTH));
    }

    private void appendTotals(StringBuilder html, List<Expense> expenses) {
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        Map<Category, BigDecimal> byCategory = new EnumMap<>(Category.class);
        for (Expense e : expenses) {
            total = total.add(e.getAmount());
            byCategory.merge(e.getCategory(), e.getAmount(), BigDecimal::add);
        }

        html.append("<div class=\"card\"><h2>Totals</h2><div class=\"totals\">");
        html.append("<div><div class=\"muted\">Total</div><div class=\"big\">$").append(total).append("</div>");
        html.append("<div class=\"muted\">").append(expenses.size()).append(" expense(s)</div></div>");
        for (Map.Entry<Category, BigDecimal> entry : byCategory.entrySet()) {
            html.append("<div><div class=\"muted\">").append(pretty(entry.getKey())).append("</div>");
            html.append("<div>$").append(entry.getValue()).append("</div></div>");
        }
        html.append("</div></div>");
    }

    private void appendFilterForm(StringBuilder html, String category, String from, String to) {
        html.append("""
                <div class="card">
                  <h2>Filter</h2>
                  <form class="row" method="get" action="/">
                    <label>Category <select name="category"><option value="">All</option>""");
        for (Category c : Category.values()) {
            boolean selected = c.name().equalsIgnoreCase(category);
            html.append("<option value=\"").append(c.name()).append("\"").append(selected ? " selected" : "")
                .append(">").append(pretty(c)).append("</option>");
        }
        html.append("""
                    </select></label>
                    <label>From <input name="from" type="date" value="%s"></label>
                    <label>To <input name="to" type="date" value="%s"></label>
                    <button type="submit">Apply</button>
                    <a href="/">Clear</a>
                  </form>
                </div>""".formatted(escape(from), escape(to)));
    }

    private void appendTable(StringBuilder html, List<Expense> expenses) {
        html.append("<div class=\"card\"><h2>Expenses</h2>");
        if (expenses.isEmpty()) {
            html.append("<p class=\"muted\">No expenses yet.</p></div>");
            return;
        }
        html.append("<table><tr><th>Date</th><th>Category</th><th>Description</th>"
                + "<th class=\"amount\">Amount</th><th></th></tr>");
        for (Expense e : expenses) {
            html.append("<tr>")
                .append("<td>").append(e.getDate()).append("</td>")
                .append("<td>").append(pretty(e.getCategory())).append("</td>")
                .append("<td>").append(escape(e.getDescription())).append("</td>")
                .append("<td class=\"amount\">$").append(e.getAmount()).append("</td>")
                .append("<td><form method=\"post\" action=\"/delete/").append(e.getId()).append("\">")
                .append("<button class=\"delete\" type=\"submit\">Delete</button></form></td>")
                .append("</tr>");
        }
        html.append("</table></div>");
    }

    // ---------------------------------------------------------------- helpers

    /** FOOD -> "Food" */
    private static String pretty(Category c) {
        String name = c.name();
        return name.charAt(0) + name.substring(1).toLowerCase();
    }

    /**
     * Makes user-typed text safe to put inside HTML. Without this, a description
     * like "<script>...</script>" would run as code in the browser (an XSS attack).
     */
    static String escape(String text) {
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(303, -1);
    }

    private static void sendHtml(HttpExchange exchange, int status, String body) throws IOException {
        String page = """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>Expense Tracker</title>
                  <style>""" + STYLE + """
                </style>
                </head>
                <body>
                  <h1>Expense Tracker</h1>
                """ + body + """
                </body>
                </html>
                """;
        byte[] bytes = page.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}

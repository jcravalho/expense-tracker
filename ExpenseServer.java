import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * REST API over HTTP, built on the JDK's com.sun.net.httpserver.
 *
 * Routes:
 *   GET    /api/health
 *   GET    /api/categories
 *   GET    /api/expenses?category=&from=&to=
 *   POST   /api/expenses
 *   GET    /api/expenses/{id}
 *   PUT    /api/expenses/{id}
 *   DELETE /api/expenses/{id}
 *   GET    /api/summary?from=&to=
 *
 * Everything outside /api/ is the HTML web page, handled by {@link WebPage}.
 */
public class ExpenseServer {
    private static final int MAX_BODY_BYTES = 64 * 1024;

    private final ExpenseManager manager;
    private final HttpServer server;
    private final ExecutorService executor;

    public ExpenseServer(ExpenseManager manager, int port) throws IOException {
        this.manager = manager;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.executor = Executors.newFixedThreadPool(8);
        server.setExecutor(executor);
        server.createContext("/api/", this::handle);
        server.createContext("/", new WebPage(manager)::handle);
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(1);
        executor.shutdown();
    }

    public int getPort() {
        return server.getAddress().getPort();
    }

    // ---------------------------------------------------------------- routing

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            route(exchange);
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());

            if (method.equals("OPTIONS")) {
                send(exchange, 204, null);
                return;
            }

            String[] parts = path.replaceAll("/+$", "").split("/");
            // parts[0] is "" and parts[1] is "api"
            String resource = parts.length > 2 ? parts[2] : "";

            switch (resource) {
                case "health" -> {
                    requireMethod(method, "GET");
                    send(exchange, 200, Map.of("status", "ok"));
                }
                case "categories" -> {
                    requireMethod(method, "GET");
                    List<String> names = new ArrayList<>();
                    for (Category c : Category.values()) {
                        names.add(c.name());
                    }
                    send(exchange, 200, names);
                }
                case "summary" -> {
                    requireMethod(method, "GET");
                    Summary s = manager.summarize(parseDate(query.get("from"), "from"), parseDate(query.get("to"), "to"));
                    send(exchange, 200, toJson(s));
                }
                case "expenses" -> {
                    if (parts.length == 3) {
                        handleCollection(exchange, method, query);
                    } else if (parts.length == 4) {
                        handleItem(exchange, method, parseId(parts[3]));
                    } else {
                        throw new NotFoundException("no route for " + path);
                    }
                }
                default -> throw new NotFoundException("no route for " + path);
            }
        } catch (ValidationException e) {
            sendError(exchange, 400, e.getMessage());
        } catch (NotFoundException e) {
            sendError(exchange, 404, e.getMessage());
        } catch (MethodNotAllowedException e) {
            exchange.getResponseHeaders().set("Allow", e.allowed);
            sendError(exchange, 405, e.getMessage());
        } catch (UncheckedIOException e) {
            System.err.println("Storage error: " + e.getCause());
            sendError(exchange, 500, "could not save data");
        } catch (RuntimeException e) {
            e.printStackTrace();
            sendError(exchange, 500, "internal server error");
        }
    }

    private void handleCollection(HttpExchange exchange, String method, Map<String, String> query) throws IOException {
        switch (method) {
            case "GET" -> {
                String cat = query.get("category");
                List<Expense> found = manager.find(
                        cat == null ? null : Category.parse(cat),
                        parseDate(query.get("from"), "from"),
                        parseDate(query.get("to"), "to"));
                List<Object> body = new ArrayList<>();
                for (Expense e : found) {
                    body.add(toJson(e));
                }
                send(exchange, 200, body);
            }
            case "POST" -> {
                ExpenseInput in = readExpenseInput(exchange);
                Expense created = manager.add(in.amount, in.category, in.date, in.description);
                exchange.getResponseHeaders().set("Location", "/api/expenses/" + created.getId());
                send(exchange, 201, toJson(created));
            }
            default -> throw new MethodNotAllowedException("GET, POST");
        }
    }

    private void handleItem(HttpExchange exchange, String method, long id) throws IOException {
        switch (method) {
            case "GET" -> send(exchange, 200, toJson(manager.get(id)));
            case "PUT" -> {
                ExpenseInput in = readExpenseInput(exchange);
                send(exchange, 200, toJson(manager.update(id, in.amount, in.category, in.date, in.description)));
            }
            case "DELETE" -> {
                manager.delete(id);
                send(exchange, 204, null);
            }
            default -> throw new MethodNotAllowedException("GET, PUT, DELETE");
        }
    }

    // ------------------------------------------------------- request parsing

    private record ExpenseInput(BigDecimal amount, Category category, LocalDate date, String description) {
    }

    private ExpenseInput readExpenseInput(HttpExchange exchange) throws IOException {
        if (!(Json.parse(readBody(exchange)) instanceof Map<?, ?> body)) {
            throw new ValidationException("request body must be a JSON object");
        }

        Object amount = body.get("amount");
        BigDecimal parsedAmount;
        if (amount instanceof BigDecimal d) {
            parsedAmount = d;
        } else if (amount instanceof String s) {
            try {
                parsedAmount = new BigDecimal(s.trim());
            } catch (NumberFormatException e) {
                throw new ValidationException("amount must be a number");
            }
        } else if (amount == null) {
            throw new ValidationException("amount is required");
        } else {
            throw new ValidationException("amount must be a number");
        }

        Category category = Category.parse(requireString(body, "category"));
        LocalDate date = parseDate(requireString(body, "date"), "date");

        Object desc = body.get("description");
        if (desc != null && !(desc instanceof String)) {
            throw new ValidationException("description must be a string");
        }
        return new ExpenseInput(parsedAmount, category, date, (String) desc);
    }

    static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                throw new ValidationException("request body too large");
            }
            String body = new String(bytes, StandardCharsets.UTF_8);
            if (body.isBlank()) {
                throw new ValidationException("request body is required");
            }
            return body;
        }
    }

    private static String requireString(Map<?, ?> body, String field) {
        Object value = body.get(field);
        if (value == null) {
            throw new ValidationException(field + " is required");
        }
        if (!(value instanceof String s)) {
            throw new ValidationException(field + " must be a string");
        }
        return s;
    }

    static LocalDate parseDate(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new ValidationException(field + " must be a date in YYYY-MM-DD format");
        }
    }

    private static long parseId(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ValidationException("id must be a whole number");
        }
    }

    static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> params = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return params;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            params.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return params;
    }

    private static void requireMethod(String method, String allowed) {
        if (!method.equals(allowed)) {
            throw new MethodNotAllowedException(allowed);
        }
    }

    private static class MethodNotAllowedException extends RuntimeException {
        final String allowed;

        MethodNotAllowedException(String allowed) {
            super("method not allowed; use " + allowed);
            this.allowed = allowed;
        }
    }

    // ------------------------------------------------------- response writing

    static Map<String, Object> toJson(Expense e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("amount", e.getAmount());
        m.put("category", e.getCategory().name());
        m.put("date", e.getDate().toString());
        m.put("description", e.getDescription());
        return m;
    }

    static Map<String, Object> toJson(Summary s) {
        Map<String, Object> byCategory = new LinkedHashMap<>();
        s.byCategory().forEach((k, v) -> byCategory.put(k.name(), v));
        Map<String, Object> byMonth = new LinkedHashMap<>();
        s.byMonth().forEach((k, v) -> byMonth.put(k.toString(), v));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", s.total());
        m.put("count", s.count());
        m.put("byCategory", byCategory);
        m.put("byMonth", byMonth);
        return m;
    }

    private static void send(HttpExchange exchange, int status, Object body) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        if (body == null) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        send(exchange, status, Map.of("error", message));
    }
}

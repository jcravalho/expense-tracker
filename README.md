# expense-tracker
A clean, Java based expense tracker. For all your spending habits.

A REST API backend written in plain Java 17+ with **no external dependencies**. It uses the
JDK's built-in `com.sun.net.httpserver` for HTTP and stores data in a CSV file.

## Running

```sh
javac -d out *.java
java -cp out Main                 # port 8080, data in ./expenses.csv
java -cp out Main 9000 data.csv   # custom port and data file
java -cp out ExpenseTrackerTests  # run the test suite
```

You can also run `Main` directly from IntelliJ.

Then open **http://localhost:8080** in your browser for the web page.

## Web page

`WebPage` serves a simple page where you can add, filter and delete expenses and see totals.
It's built entirely in Java: the server writes the HTML and the browser just displays it.
It uses **no JavaScript**, only HTML forms:

| Route | What it does |
|---|---|
| `GET /` | Shows the page. Optional filters: `?category=&from=&to=` |
| `POST /add` | Adds an expense from the form, then redirects back to `/` |
| `POST /delete/{id}` | Deletes an expense, then redirects back to `/` |

After a form is submitted, the server replies with a redirect to `/` (the *Post/Redirect/Get*
pattern), so refreshing the browser doesn't submit the form twice. Errors are shown in a red
box at the top of the page. User-typed text is HTML-escaped so it can't inject code into the page.

## Architecture

| Class | Responsibility |
|---|---|
| `Main` | Entry point: wires storage, manager and server together |
| `WebPage` | The HTML web page (no JavaScript): builds the page and handles its forms |
| `ExpenseServer` | HTTP layer: routing, request parsing, JSON responses, error → status code mapping |
| `ExpenseManager` | Business logic: CRUD, filtering, summaries; thread-safe; rolls back if a save fails |
| `FileStorage` | CSV persistence with quoting and atomic writes (temp file + move) |
| `Expense` | Immutable domain object; validates its own fields |
| `Category` | Enum of spending categories |
| `Summary` | Record holding aggregated totals |
| `Json` | Minimal JSON parser/writer |
| `ValidationException` / `NotFoundException` | Map to HTTP 400 / 404 |

Requests flow **HTTP → `ExpenseServer` → `ExpenseManager` → `FileStorage`**. Each layer only knows
about the one below it.

Design notes:
- Money is stored as `BigDecimal` with 2 decimal places, never `double`, so there is no rounding error.
- Dates use `java.time.LocalDate` (ISO `YYYY-MM-DD`).
- `Expense` is immutable; an update replaces the object instead of changing it.

## API

Base URL: `http://localhost:8080/api`

| Method | Path | Description |
|---|---|---|
| GET | `/health` | Health check |
| GET | `/categories` | List valid categories |
| GET | `/expenses` | List expenses, newest first. Optional query params: `category`, `from`, `to` (inclusive dates) |
| POST | `/expenses` | Create an expense → `201` with `Location` header |
| GET | `/expenses/{id}` | Get one expense |
| PUT | `/expenses/{id}` | Replace an expense |
| DELETE | `/expenses/{id}` | Delete an expense → `204` |
| GET | `/summary` | Total, count, totals by category and by month. Optional `from`, `to` |

Request body for POST and PUT:

```json
{ "amount": 12.50, "category": "FOOD", "date": "2026-10-01", "description": "Lunch" }
```

`amount` must be > 0 with at most 2 decimal places. `category` is case-insensitive.
`description` is optional (max 255 characters).

Errors are returned as `{"error": "message"}` with status `400` (bad input), `404` (not found),
`405` (wrong method) or `500` (server or storage failure).

### Examples

```sh
curl -X POST localhost:8080/api/expenses \
     -d '{"amount": 12.50, "category": "food", "date": "2026-10-01", "description": "Lunch"}'

curl "localhost:8080/api/expenses?category=food&from=2026-10-01&to=2026-10-31"

curl localhost:8080/api/summary
# {"total":12.50,"count":1,"byCategory":{"FOOD":12.50},"byMonth":{"2026-10":12.50}}
```

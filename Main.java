import java.nio.file.Path;

/**
 * Entry point. Starts the expense tracker REST API.
 *
 * Usage: java Main [port] [dataFile]
 *   port     defaults to 8080
 *   dataFile defaults to expenses.csv in the working directory
 */
public class Main {
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        Path dataFile = Path.of(args.length > 1 ? args[1] : "expenses.csv");

        ExpenseManager manager = new ExpenseManager(new FileStorage(dataFile));
        ExpenseServer server = new ExpenseServer(manager, port);
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));

        System.out.println("Loaded " + manager.size() + " expense(s) from " + dataFile.toAbsolutePath());
        System.out.println("Expense Tracker API running at http://localhost:" + server.getPort() + "/api");
    }
}

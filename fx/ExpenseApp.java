import java.nio.file.Path;
import java.util.List;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * Entry point for the JavaFX desktop app. The layout lives in expense-tracker.fxml
 * (edit it in SceneBuilder); the behaviour lives in ExpenseController.
 *
 * Usage: java ExpenseApp [dataFile]
 *   dataFile defaults to expenses.csv in the working directory
 */
public class ExpenseApp extends Application {
    @Override
    public void start(Stage stage) throws Exception {
        List<String> args = getParameters().getRaw();
        Path dataFile = Path.of(args.isEmpty() ? "expenses.csv" : args.get(0));
        ExpenseManager manager = new ExpenseManager(new FileStorage(dataFile));

        FXMLLoader loader = new FXMLLoader(ExpenseApp.class.getResource("/expense-tracker.fxml"));
        Parent root = loader.load();
        loader.<ExpenseController>getController().setManager(manager);

        stage.setTitle("Expense Tracker");
        stage.setScene(new Scene(root));
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}

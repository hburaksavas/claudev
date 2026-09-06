package dev.claudev.ui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * The JavaFX half of the single-JVM lifecycle (D6, D15): {@code app-bootstrap} starts the Spring
 * context first, then calls {@link Application#launch(Class, String...)} against this class.
 * Window close hides the stage rather than exiting the JVM ({@code setImplicitExit(false)}); only
 * an explicit {@link #quit()} — wired to a tray "Quit" action or menu item by app-bootstrap — runs
 * the graceful-shutdown hook and actually exits.
 */
public class ClaudevShell extends Application {

    private static volatile Runnable onQuitRequested = () -> { };

    /** app-bootstrap wires this to "run the operation engine's graceful Stop All, then settle." */
    public static void setOnQuitRequested(Runnable handler) {
        onQuitRequested = handler == null ? () -> { } : handler;
    }

    @Override
    public void start(Stage primaryStage) {
        Platform.setImplicitExit(false);

        Label label = new Label("claudev — dev workspace orchestrator (skeleton)");
        Scene scene = new Scene(new StackPane(label), 640, 400);

        primaryStage.setTitle("claudev");
        primaryStage.setScene(scene);
        primaryStage.setOnCloseRequest(event -> {
            event.consume();
            primaryStage.hide();
        });

        primaryStage.show();
    }

    /** Runs the app's own graceful-shutdown hook, then exits the JVM. Never called implicitly. */
    public static void quit() {
        onQuitRequested.run();
        Platform.exit();
    }
}

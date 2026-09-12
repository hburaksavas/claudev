package dev.claudev.ui;

import dev.claudev.domain.Instance;
import dev.claudev.domain.InstanceId;
import dev.claudev.domain.InstanceState;
import dev.claudev.domain.OperationEvent;
import dev.claudev.domain.OperationId;
import dev.claudev.domain.Workspace;
import dev.claudev.domain.WorkspaceId;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * WP5: workspace/instance list bound to live reconciler-derived state, start/stop dispatched as
 * real operations, and a live event log for whichever operation is currently tracked. Polls
 * {@link WorkspaceControlPort} on a background thread every 2s (SQLite reads are blocking, so this
 * must never run on the FX thread) and applies results via {@link Platform#runLater}.
 */
public final class WorkspacesPane extends BorderPane {

    private final WorkspaceControlPort controlPort;
    /**
     * A real responsiveness bug found from user feedback: this pool used to be single-threaded,
     * shared between the periodic 2s refresh and every user action (create/start/stop/plugin calls)
     * submitted via {@link #runAsync}. A slow action — a RabbitMQ plugin enable/disable can take up
     * to ~30s of real spawn-and-verify time — queued the periodic refresh behind it, so the table
     * stopped updating and the UI looked stuck for the whole duration. Two threads means the
     * scheduled refresh and a one-off action never wait on each other.
     */
    private final ScheduledExecutorService poller = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "claudev-workspaces-poll");
        thread.setDaemon(true);
        return thread;
    });

    private final ObservableList<Workspace> workspaceRows = FXCollections.observableArrayList();
    private final ObservableList<Instance> instanceRows = FXCollections.observableArrayList();
    private final TableView<Workspace> workspaceTable = new TableView<>(workspaceRows);
    private final TableView<Instance> instanceTable = new TableView<>(instanceRows);
    private final TextArea eventLog = new TextArea();

    private volatile WorkspaceId selectedWorkspaceId;
    private volatile OperationId trackedOperationId;

    public WorkspacesPane(WorkspaceControlPort controlPort) {
        this.controlPort = controlPort;

        setPadding(new Insets(16));
        setTop(new VBox(6, subtitle(), header()));
        setCenter(splitPane());

        controlPort.subscribeToOperationEvents(this::onOperationEvent);
        poller.scheduleWithFixedDelay(this::refreshInBackground, 0, 2, TimeUnit.SECONDS);
    }

    /** Call when the shell is hidden/closed for good — stops the polling thread. */
    public void shutdown() {
        poller.shutdownNow();
    }

    private Label subtitle() {
        Label label = new Label(
                "A workspace is just a named group: create one, then add RabbitMQ (or test-only \"dummy\") "
                        + "instances to it and start/stop them. Pick a workspace on the left to see its instances.");
        label.setWrapText(true);
        label.setStyle("-fx-text-fill: #666; -fx-font-size: 11px;");
        return label;
    }

    private HBox header() {
        Button newWorkspace = new Button("New workspace...");
        newWorkspace.setOnAction(e -> promptNewWorkspace());

        Button deleteWorkspace = new Button("Delete workspace");
        deleteWorkspace.setOnAction(e -> deleteSelectedWorkspace());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox header = new HBox(8, newWorkspace, deleteWorkspace, spacer);
        header.setPadding(new Insets(0, 0, 12, 0));
        return header;
    }

    private SplitPane splitPane() {
        workspaceTable.getColumns().add(workspaceColumn("Workspace", Workspace::name));
        workspaceTable.setPrefWidth(240);
        workspaceTable.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            selectedWorkspaceId = selected == null ? null : selected.id();
            refreshInBackground();
        });

        VBox instancesPane = new VBox(8, instanceHeader(), instanceTableWithColumns(), new Label("Operation log"), eventLog);
        VBox.setVgrow(instanceTable, Priority.ALWAYS);
        VBox.setVgrow(eventLog, Priority.SOMETIMES);
        eventLog.setEditable(false);
        eventLog.setPrefRowCount(8);

        SplitPane split = new SplitPane(workspaceTable, instancesPane);
        split.setOrientation(Orientation.HORIZONTAL);
        split.setDividerPositions(0.25);
        return split;
    }

    private HBox instanceHeader() {
        Button newInstance = new Button("New dummy instance...");
        newInstance.setOnAction(e -> promptNewInstance());

        Button newRabbitMq = new Button("New RabbitMQ instance...");
        newRabbitMq.setOnAction(e -> promptNewRabbitMqInstance());

        Button start = new Button("Start");
        start.setOnAction(e -> dispatch(controlPort::startInstance, "start"));

        Button stop = new Button("Stop");
        stop.setOnAction(e -> dispatch(controlPort::stopInstance, "stop"));

        Button delete = new Button("Delete instance");
        delete.setOnAction(e -> deleteSelectedInstance());

        Button plugins = new Button("Plugins...");
        plugins.setOnAction(e -> openPluginsDialog());

        return new HBox(8, newInstance, newRabbitMq, start, stop, delete, plugins);
    }

    private TableView<Instance> instanceTableWithColumns() {
        instanceTable.getColumns().add(column("Instance", Instance::name));
        instanceTable.getColumns().add(stateColumn());
        instanceTable.getColumns().add(column("Desired", i -> i.desiredState().name()));
        return instanceTable;
    }

    private TableColumn<Instance, String> stateColumn() {
        TableColumn<Instance, String> col = column("State", i -> i.observedState().name());
        col.setCellFactory(c -> new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || value == null) {
                    setText(null);
                    setStyle("");
                    return;
                }
                setText(value);
                setStyle("-fx-text-fill: " + colorFor(InstanceState.valueOf(value)) + ";");
            }
        });
        return col;
    }

    private static String colorFor(InstanceState state) {
        return switch (state) {
            case RUNNING -> "#1a7f37";
            case ORPHANED, UNTRACKED -> "#c22";
            case STARTING, STOPPING -> "#9a6700";
            default -> "#666";
        };
    }

    private <T> TableColumn<Instance, String> column(String heading, java.util.function.Function<Instance, T> value) {
        TableColumn<Instance, String> col = new TableColumn<>(heading);
        col.setCellValueFactory(data -> new SimpleStringProperty(String.valueOf(value.apply(data.getValue()))));
        return col;
    }

    private TableColumn<Workspace, String> workspaceColumn(String heading, java.util.function.Function<Workspace, String> value) {
        TableColumn<Workspace, String> col = new TableColumn<>(heading);
        col.setCellValueFactory(data -> new SimpleStringProperty(value.apply(data.getValue())));
        return col;
    }

    private void promptNewWorkspace() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("New workspace");
        dialog.setHeaderText(null);
        dialog.setContentText("Name:");
        dialog.showAndWait().filter(name -> !name.isBlank()).ifPresent(name -> {
            runAsync(() -> controlPort.createWorkspace(name));
        });
    }

    private void promptNewInstance() {
        promptNewInstance("New dummy instance", controlPort::createDummyInstance);
    }

    private void promptNewRabbitMqInstance() {
        promptNewInstance("New RabbitMQ instance", controlPort::createRabbitMqInstance);
    }

    private void promptNewInstance(String title, java.util.function.BiFunction<WorkspaceId, String, Instance> create) {
        WorkspaceId workspaceId = selectedWorkspaceId;
        if (workspaceId == null) {
            return;
        }
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle(title);
        dialog.setHeaderText(null);
        dialog.setContentText("Name:");
        dialog.showAndWait().filter(name -> !name.isBlank()).ifPresent(name -> {
            runAsync(() -> create.apply(workspaceId, name));
        });
    }

    private void deleteSelectedWorkspace() {
        Workspace selected = workspaceTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        confirm("Delete workspace \"" + selected.name() + "\"?").ifPresent(yes -> runAsync(() -> {
            controlPort.deleteWorkspace(selected.id());
            return null;
        }));
    }

    private void deleteSelectedInstance() {
        Instance selected = instanceTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        confirm("Delete instance \"" + selected.name() + "\"?").ifPresent(yes -> runAsync(() -> {
            controlPort.deleteInstance(selected.id());
            return null;
        }));
    }

    /** WP10c: RabbitMQ-only — {@link WorkspaceControlPort#listEnabledPlugins} throws for any other instance kind, surfaced here as a plain error dialog rather than special-cased in the UI. */
    private void openPluginsDialog() {
        Instance selected = instanceTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        InstanceId instanceId = selected.id();

        javafx.scene.control.ListView<String> enabledList = new javafx.scene.control.ListView<>();
        javafx.scene.control.TextField pluginName = new javafx.scene.control.TextField();
        pluginName.setPromptText("e.g. rabbitmq_shovel");

        Runnable refreshPlugins = () -> runAsync(() -> {
            List<String> plugins = controlPort.listEnabledPlugins(instanceId);
            Platform.runLater(() -> enabledList.getItems().setAll(plugins));
            return null;
        });

        Button enable = new Button("Enable");
        enable.setOnAction(e -> {
            String name = pluginName.getText().trim();
            if (!name.isBlank()) {
                runAsync(() -> {
                    controlPort.enablePlugin(instanceId, name);
                    Platform.runLater(refreshPlugins);
                    return null;
                });
            }
        });

        Button disable = new Button("Disable");
        disable.setOnAction(e -> {
            String name = pluginName.getText().trim();
            if (!name.isBlank()) {
                runAsync(() -> {
                    controlPort.disablePlugin(instanceId, name);
                    Platform.runLater(refreshPlugins);
                    return null;
                });
            }
        });

        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> refreshPlugins.run());

        VBox content = new VBox(8,
                new Label("Enabled plugins (" + selected.name() + ")"), enabledList,
                new HBox(8, pluginName, enable, disable, refresh));
        content.setPadding(new Insets(12));
        enabledList.setPrefHeight(160);

        javafx.scene.control.Dialog<Void> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle("RabbitMQ plugins");
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        refreshPlugins.run();
        dialog.showAndWait();
    }

    private Optional<ButtonType> confirm(String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.YES, ButtonType.CANCEL);
        alert.setHeaderText(null);
        return alert.showAndWait().filter(bt -> bt == ButtonType.YES);
    }

    private void dispatch(java.util.function.Function<InstanceId, OperationId> action, String label) {
        Instance selected = instanceTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        runAsync(() -> {
            OperationId operationId = action.apply(selected.id());
            trackedOperationId = operationId;
            Platform.runLater(() -> eventLog.appendText("[" + label + "] operation " + operationId.value() + " submitted\n"));
            return null;
        });
    }

    private <T> void runAsync(java.util.concurrent.Callable<T> work) {
        poller.submit(() -> {
            try {
                work.call();
                refreshInBackground();
            } catch (Exception e) {
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR, e.getMessage(), ButtonType.OK);
                    alert.setHeaderText(null);
                    alert.showAndWait();
                });
            }
        });
    }

    private void refreshInBackground() {
        try {
            List<Workspace> workspaces = controlPort.listWorkspaces();
            WorkspaceId keepSelected = selectedWorkspaceId;
            List<Instance> instances = keepSelected == null ? List.of() : controlPort.listInstances(keepSelected);

            Platform.runLater(() -> {
                // A real freeze found from user reproduction, not assumed: TableView.setAll(), even when
                // the new list is equal in content, resets the selection/focus model, which fires a
                // Windows accessibility notification (WinAccessible.sendNotification) on every single
                // call. On this machine that native call is slow enough that doing it unconditionally
                // every 2s pegs the FX Application Thread and starves all real user input behind it —
                // confirmed by a thread dump showing the JavaFX Application Thread stuck in exactly that
                // call chain (TableView selection churn -> notifyAccessibleAttributeChanged). Skipping
                // the list replace when nothing actually changed avoids the selection-model churn (and
                // therefore the notification) on every poll that finds no new data, which is the common
                // case.
                if (!workspaceRows.equals(workspaces)) {
                    Workspace previouslySelected = workspaceTable.getSelectionModel().getSelectedItem();
                    workspaceRows.setAll(workspaces);
                    if (previouslySelected != null) {
                        workspaces.stream().filter(w -> w.id().equals(previouslySelected.id())).findFirst()
                                .ifPresent(w -> workspaceTable.getSelectionModel().select(w));
                    }
                }
                if (!instanceRows.equals(instances)) {
                    instanceRows.setAll(instances);
                }
            });
        } catch (RuntimeException e) {
            Platform.runLater(() -> eventLog.appendText("[poll error] " + e.getMessage() + "\n"));
        }
    }

    private void onOperationEvent(OperationEvent event) {
        OperationId tracked = trackedOperationId;
        if (tracked == null || !event.operationId().equals(tracked)) {
            return;
        }
        String line = DateTimeFormatter.ISO_INSTANT.format(event.timestamp())
                + " [" + event.kind() + "] " + event.nodeId() + ": " + event.message() + "\n";
        Platform.runLater(() -> eventLog.appendText(line));
    }
}

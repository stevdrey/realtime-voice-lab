package io.github.stevdrey.realtimevoicelab.ui;

import java.nio.file.NoSuchFileException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import io.github.stevdrey.realtimevoicelab.ui.theme.ThemeProfileStore;
import io.github.stevdrey.realtimevoicelab.ui.theme.ThemeProfileStore.InvalidProfileException;
import io.github.stevdrey.realtimevoicelab.ui.theme.ThemeTokens;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

public final class ThemeProfilesController {
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(Thread.ofVirtual().name("rvl-theme-profiles").factory());
    private ThemeProfileStore store;
    private WorkspaceTheme theme;
    private Button opener;
    private VBox workspace;
    private Task<?> pending;
    private Node restoreFocus;

    @FXML private StackPane overlay;
    @FXML private VBox actions;
    @FXML private ComboBox<String> profile;
    @FXML private Label status;
    @FXML private Button closeButton;
    @FXML private HBox recordingIndicator;
    @FXML private Label recordingLabel;

    void attach(Button opener, VBox workspace, ThemeProfileStore store, WorkspaceTheme theme) {
        this.opener = opener;
        this.workspace = workspace;
        this.store = store;
        this.theme = theme;
        overlay.managedProperty().bind(overlay.visibleProperty());
        recordingIndicator.managedProperty().bind(recordingIndicator.visibleProperty());
        profile.getEditor().setTextFormatter(TextInputLimit.formatter(ThemeProfileStore.MAX_NAME_LENGTH));
        opener.setOnAction(event -> open());
        overlay.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                if (profile.isShowing()) {
                    profile.hide();
                } else {
                    close();
                }
                event.consume();
            }
        });
    }

    void renderRecording(boolean recording, String status) {
        recordingLabel.setText(recording ? status : "");
        recordingIndicator.setVisible(recording);
    }

    private void open() {
        // Stay in the same window so the session's focus-loss handler does not stop capture.
        overlay.setVisible(true);
        workspace.setDisable(true);
        closeButton.requestFocus();
        if (pending == null) {
            runIo(store::names, names -> {
                var typedName = profile.getEditor().getText();
                profile.getItems().setAll(names);
                profile.getEditor().setText(typedName);
            });
        }
    }

    @FXML
    private void close() {
        overlay.setVisible(false);
        workspace.setDisable(false);
        opener.requestFocus();
    }

    @FXML
    private void useOcean() {
        apply(WorkspaceTheme.OCEAN, "Ocean theme applied.");
    }

    @FXML
    private void reset() {
        apply(ThemeTokens.defaults(), "Default theme applied.");
    }

    @FXML
    private void save() {
        withName(name -> {
            var snapshot = theme.current();
            runIo(() -> {
                store.save(name, snapshot);
                return name;
            }, saved -> {
                if (!profile.getItems().contains(saved)) {
                    profile.getItems().add(saved);
                    profile.getItems().sort(String::compareTo);
                }
                selectName(saved);
                status.setText("Saved profile “" + saved + "”.");
            });
        });
    }

    @FXML
    private void load() {
        withName(name -> runIo(() -> store.load(name), tokens -> {
            apply(tokens, "Loaded profile “" + name + "”.");
            selectName(name);
        }));
    }

    void dispose() {
        if (pending != null) {
            pending.cancel(true);
            pending = null;
        }
        executor.shutdownNow();
    }

    private void selectName(String name) {
        profile.setValue(name);
        profile.getEditor().setText(name);
    }

    private void withName(Consumer<String> action) {
        String name;
        try {
            name = ThemeProfileStore.normalizeName(profile.getEditor().getText());
        } catch (IllegalArgumentException invalidName) {
            status.setText(invalidName.getMessage());
            return;
        }
        action.accept(name);
    }

    private void apply(ThemeTokens tokens, String message) {
        boolean applied = theme.apply(overlay.getScene(), tokens);
        status.setText(applied ? message : "Theme is invalid. Default theme applied.");
    }

    private <T> void runIo(Callable<T> operation, Consumer<T> onSuccess) {
        var task = new Task<T>() {
            @Override
            protected T call() throws Exception {
                return operation.call();
            }
        };
        pending = task;
        // Disabling the actions moves focus away from the control that started the operation.
        restoreFocus = null;
        for (Node node = overlay.getScene() == null ? null : overlay.getScene().getFocusOwner();
                node != null; node = node.getParent()) {
            if (node == actions) {
                restoreFocus = overlay.getScene().getFocusOwner();
                break;
            }
        }
        actions.setDisable(true);
        task.setOnSucceeded(event -> {
            if (pending == task) {
                finishIo();
                onSuccess.accept(task.getValue());
            }
        });
        task.setOnFailed(event -> {
            if (pending == task) {
                finishIo();
                status.setText(switch (task.getException()) {
                    case NoSuchFileException missing -> "Theme profile was not found.";
                    case InvalidProfileException invalid -> invalid.getMessage();
                    default -> "Theme profiles are unavailable. Try again.";
                });
            }
        });
        executor.execute(task);
    }

    private void finishIo() {
        pending = null;
        actions.setDisable(false);
        if (restoreFocus != null && overlay.isVisible()) {
            restoreFocus.requestFocus();
        }
        restoreFocus = null;
    }
}

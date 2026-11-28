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
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

public final class ThemeProfilesController {
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(Thread.ofVirtual().name("rvl-theme-profiles").factory());
    private ThemeProfileStore store;
    private WorkspaceTheme theme;
    private Task<?> pending;

    @FXML private VBox panel;
    @FXML private FlowPane actions;
    @FXML private ComboBox<String> profile;
    @FXML private Label status;

    void attach(ToggleButton toggle, ThemeProfileStore store, WorkspaceTheme theme) {
        this.store = store;
        this.theme = theme;
        panel.visibleProperty().bind(toggle.selectedProperty());
        panel.managedProperty().bind(panel.visibleProperty());
        profile.getEditor().setTextFormatter(TextInputLimit.formatter(ThemeProfileStore.MAX_NAME_LENGTH));
        toggle.selectedProperty().addListener((observable, previous, open) -> {
            if (open && pending == null) {
                runIo(store::names, names -> {
                    var typedName = profile.getEditor().getText();
                    profile.getItems().setAll(names);
                    profile.getEditor().setText(typedName);
                });
            }
        });
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
        boolean applied = theme.apply(panel.getScene(), tokens);
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
    }
}

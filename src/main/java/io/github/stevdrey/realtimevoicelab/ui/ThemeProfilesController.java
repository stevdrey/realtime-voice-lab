package io.github.stevdrey.realtimevoicelab.ui;

import java.nio.file.NoSuchFileException;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import io.github.stevdrey.realtimevoicelab.realtime.RealtimeSession;
import io.github.stevdrey.realtimevoicelab.ui.theme.ThemeProfileStore;
import io.github.stevdrey.realtimevoicelab.ui.theme.ThemeProfileStore.InvalidProfileException;
import io.github.stevdrey.realtimevoicelab.ui.theme.ThemeTokens;
import io.github.stevdrey.realtimevoicelab.ui.theme.WorkspaceAppearance;
import io.github.stevdrey.realtimevoicelab.ui.theme.WorkspaceAppearance.BuiltIn;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

public final class ThemeProfilesController {
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(Thread.ofVirtual().name("rvl-theme-profiles").factory());
    private final Set<KeyCode> heldThemeActionKeys = EnumSet.noneOf(KeyCode.class);
    private ThemeProfileStore store;
    private WorkspaceTheme theme;
    private RealtimeSession session;
    private WorkspaceAppearance renderedAppearance;
    private Button opener;
    private VBox workspace;
    private Task<?> pending;
    private Node restoreFocus;
    private KeyCode themeActionKey;

    @FXML private StackPane overlay;
    @FXML private VBox actions;
    @FXML private ComboBox<String> profile;
    @FXML private Label status;
    @FXML private HBox appearanceActions;
    @FXML private Button keepPreviewButton;
    @FXML private Button discardPreviewButton;
    @FXML private Button undoButton;
    @FXML private Button closeButton;
    @FXML private HBox recordingIndicator;
    @FXML private Label recordingLabel;
    @FXML private Region recordingRing;
    @FXML private Button stopListeningButton;

    void attach(Button opener, VBox workspace, StackPane sceneRoot, ThemeProfileStore store, WorkspaceTheme theme,
                RealtimeSession session, Runnable stopListening) {
        this.opener = opener;
        this.workspace = workspace;
        this.store = store;
        this.theme = theme;
        this.session = session;
        overlay.managedProperty().bind(overlay.visibleProperty());
        appearanceActions.managedProperty().bind(appearanceActions.visibleProperty());
        keepPreviewButton.managedProperty().bind(keepPreviewButton.visibleProperty());
        discardPreviewButton.managedProperty().bind(discardPreviewButton.visibleProperty());
        undoButton.managedProperty().bind(undoButton.visibleProperty());
        recordingIndicator.managedProperty().bind(recordingIndicator.visibleProperty());
        recordingRing.managedProperty().bind(recordingRing.visibleProperty());
        stopListeningButton.managedProperty().bind(stopListeningButton.visibleProperty());
        stopListeningButton.setOnAction(event -> stopListening.run());
        profile.getEditor().setTextFormatter(TextInputLimit.formatter(ThemeProfileStore.MAX_NAME_LENGTH));
        opener.setOnAction(event -> open());
        sceneRoot.addEventFilter(KeyEvent.KEY_PRESSED, this::pressThemeActionKey);
        sceneRoot.addEventFilter(KeyEvent.KEY_RELEASED, this::releaseThemeActionKey);
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

    private void pressThemeActionKey(KeyEvent event) {
        var key = event.getCode();
        if (heldThemeActionKeys.contains(key)) {
            // A repeat must not rearm the button after a theme update or focus change.
            event.consume();
        } else if ((key == KeyCode.SPACE || key == KeyCode.ENTER)
                && (event.getTarget() == keepPreviewButton || event.getTarget() == undoButton)) {
            heldThemeActionKeys.add(key);
            if (themeActionKey == null) {
                themeActionKey = key;
            } else {
                event.consume();
            }
        }
    }

    private void releaseThemeActionKey(KeyEvent event) {
        var key = event.getCode();
        if (heldThemeActionKeys.remove(key)) {
            if (key == themeActionKey) {
                // The button still receives the matching release and resets its keyboard state.
                themeActionKey = null;
            } else {
                event.consume();
            }
        }
    }

    void cancelThemeKeyPresses() {
        disarmHeldThemeAction();
        heldThemeActionKeys.clear();
        themeActionKey = null;
    }

    private void disarmHeldThemeAction() {
        if (themeActionKey != null) {
            keepPreviewButton.disarm();
            undoButton.disarm();
        }
    }

    void renderTheme(WorkspaceAppearance appearance) {
        if (renderedAppearance == appearance) {
            return;
        }
        if (!appearance.equals(renderedAppearance)) {
            disarmHeldThemeAction();
        }
        renderedAppearance = appearance;
        if (overlay.getScene() != null) {
            theme.apply(overlay.getScene(), appearance.displayed());
        }
        var preview = appearance.preview();
        boolean previewing = preview != null;
        boolean showUndo = !previewing && appearance.previous() != null;
        if ((!previewing && (keepPreviewButton.isFocused() || discardPreviewButton.isFocused()))
                || (!showUndo && undoButton.isFocused())) {
            closeButton.requestFocus();
        }
        keepPreviewButton.setVisible(previewing);
        discardPreviewButton.setVisible(previewing);
        undoButton.setVisible(showUndo);
        appearanceActions.setVisible(previewing || showUndo);
        opener.setText(previewing ? "Theme preview" : "Theme");
        status.setText(previewing ? "Previewing " + preview.label() + "." : switch (appearance.committedId()) {
            case "default" -> "Default theme applied.";
            case "ocean" -> "Ocean theme applied.";
            default -> "Custom theme applied.";
        });
    }

    void renderRecording(boolean recording, boolean listening, String status) {
        boolean showCaptureState = recording || listening;
        recordingLabel.setText(showCaptureState ? status : "");
        recordingIndicator.setVisible(showCaptureState);
        recordingRing.setVisible(recording);
        if (!listening && stopListeningButton.isFocused()) {
            closeButton.requestFocus();
        }
        stopListeningButton.setVisible(listening);
    }

    private void open() {
        // Stay in the same window so the session's focus-loss handler does not stop capture.
        renderTheme(session.appearance());
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
        session.discardWorkspaceThemePreview();
        renderTheme(session.appearance());
        overlay.setVisible(false);
        workspace.setDisable(false);
        opener.requestFocus();
    }

    @FXML
    private void useOcean() {
        apply(BuiltIn.OCEAN.tokens(), "Ocean theme applied.");
    }

    @FXML
    private void reset() {
        apply(ThemeTokens.defaults(), "Default theme applied.");
    }

    @FXML
    private void keepPreview() {
        session.keepWorkspaceThemePreview(renderedAppearance);
        renderTheme(session.appearance());
    }

    @FXML
    private void discardPreview() {
        session.discardWorkspaceThemePreview();
        renderTheme(session.appearance());
    }

    @FXML
    private void undoTheme() {
        session.undoWorkspaceTheme(renderedAppearance);
        renderTheme(session.appearance());
    }

    @FXML
    private void save() {
        withName(name -> {
            var snapshot = session.appearance().committed();
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
        boolean applied = session.selectWorkspaceTheme(tokens);
        renderTheme(session.appearance());
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
        // Park focus on the overlay while the action is disabled so key repeats cannot activate Close.
        restoreFocus = null;
        var scene = overlay.getScene();
        var focused = scene == null ? null : scene.getFocusOwner();
        for (Node node = focused; node != null; node = node.getParent()) {
            if (node == actions) {
                restoreFocus = focused;
                overlay.requestFocus();
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
        if (restoreFocus != null && overlay.isVisible() && overlay.getScene().getFocusOwner() == overlay) {
            restoreFocus.requestFocus();
        }
        restoreFocus = null;
    }
}

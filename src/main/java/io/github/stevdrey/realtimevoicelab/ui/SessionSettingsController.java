package io.github.stevdrey.realtimevoicelab.ui;

import java.util.function.Predicate;

import io.github.stevdrey.realtimevoicelab.realtime.RealtimeSession.SettingsView;
import io.github.stevdrey.realtimevoicelab.realtime.SessionSettings;
import io.github.stevdrey.realtimevoicelab.realtime.SessionSettings.Mode;
import io.github.stevdrey.realtimevoicelab.realtime.SessionSettings.Model;
import io.github.stevdrey.realtimevoicelab.realtime.SessionSettings.Preset;
import io.github.stevdrey.realtimevoicelab.realtime.SessionSettings.ToolPreset;
import io.github.stevdrey.realtimevoicelab.realtime.SessionSettings.TurnDetection;
import io.github.stevdrey.realtimevoicelab.realtime.SessionSettings.Voice;
import javafx.beans.binding.Bindings;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.stage.Stage;

public final class SessionSettingsController {
    private final Stage dialog;
    private final SettingsView settings;
    private final boolean connected;
    private final Predicate<SessionSettings> apply;
    private Mode unrestrictedMode;
    private ToolPreset unrestrictedTools;

    @FXML private ComboBox<Model> model;
    @FXML private ComboBox<Voice> voice;
    @FXML private ComboBox<Mode> mode;
    @FXML private ComboBox<TurnDetection> turnDetection;
    @FXML private ComboBox<Preset> preset;
    @FXML private ComboBox<ToolPreset> toolPreset;
    @FXML private TextArea instructions;
    @FXML private Label reconnectHelp;
    @FXML private Label error;
    @FXML private Button applyButton;

    SessionSettingsController(Stage dialog, SettingsView settings, boolean connected, Predicate<SessionSettings> apply) {
        this.dialog = dialog;
        this.settings = settings;
        this.connected = connected;
        this.apply = apply;
    }

    @FXML
    private void initialize() {
        var current = settings.values();
        model.getItems().setAll(Model.values());
        model.setValue(current.model());
        model.setDisable(connected);
        voice.getItems().setAll(Voice.values());
        voice.setValue(current.voice());
        voice.setDisable(settings.voiceLocked());
        mode.getItems().setAll(Mode.values());
        mode.setValue(current.mode());
        unrestrictedMode = current.mode();
        unrestrictedTools = current.restrictedInterviewer() ? SessionSettings.DEFAULT.toolPreset() : current.toolPreset();
        turnDetection.getItems().setAll(TurnDetection.values());
        turnDetection.setValue(current.turnDetection());
        turnDetection.disableProperty().bind(mode.valueProperty().isEqualTo(Mode.TEXT));
        toolPreset.getItems().setAll(ToolPreset.DEMO_COUNTER, ToolPreset.TOY_WORKSPACE);
        if (current.restrictedInterviewer()) {
            toolPreset.getItems().add(ToolPreset.NONE);
        }
        toolPreset.setValue(current.toolPreset());
        preset.getItems().setAll(Preset.values());
        if (connected && !current.restrictedInterviewer()) {
            preset.getItems().remove(Preset.RESTRICTED_INTERVIEWER);
        }
        instructions.setText(current.instructions());
        instructions.setTextFormatter(TextInputLimit.formatter(SessionSettings.MAX_INSTRUCTION_CHARS));
        for (var option : preset.getItems()) {
            if (option == Preset.RESTRICTED_INTERVIEWER && !current.restrictedInterviewer()) {
                continue;
            }
            if (option.instructions().equals(current.instructions())) {
                preset.setValue(option);
                break;
            }
        }
        var restricted = preset.valueProperty().isEqualTo(Preset.RESTRICTED_INTERVIEWER);
        preset.setDisable(connected && current.restrictedInterviewer());
        mode.disableProperty().bind(restricted);
        toolPreset.disableProperty().bind(Bindings.createBooleanBinding(() -> connected || restricted.get(), restricted));
        instructions.editableProperty().bind(restricted.not());
        preset.valueProperty().addListener((observable, previous, selected) -> {
            if (selected == Preset.RESTRICTED_INTERVIEWER) {
                unrestrictedMode = mode.getValue();
                unrestrictedTools = toolPreset.getValue();
                mode.setValue(Mode.TEXT);
                toolPreset.getItems().add(ToolPreset.NONE);
                toolPreset.setValue(ToolPreset.NONE);
            } else if (previous == Preset.RESTRICTED_INTERVIEWER) {
                mode.setValue(unrestrictedMode);
                toolPreset.setValue(unrestrictedTools);
                toolPreset.getItems().remove(ToolPreset.NONE);
            }
            if (selected != null) {
                instructions.setText(selected.instructions());
            }
        });
        instructions.textProperty().addListener((observable, previous, edited) -> {
            var selected = preset.getValue();
            if (selected != null && selected != Preset.RESTRICTED_INTERVIEWER
                    && !selected.instructions().equals(edited)) {
                preset.setValue(null);
            }
        });
        applyButton.setText(connected ? "Apply" : "Save");
        var defaultHelp = connected
                ? settings.voiceLocked()
                    ? "Disconnect and reconnect to change the model, voice, or tool preset."
                    : "Disconnect and reconnect to change the model or tool preset."
                : "Saved settings apply when you connect.";
        if (connected) {
            defaultHelp += " Select Restricted Interviewer while disconnected.";
        }
        reconnectHelp.textProperty().bind(Bindings.when(restricted)
                .then(connected ? "Disconnect to leave Restricted Interviewer."
                        : "Restricted Interviewer uses text, fixed instructions, and no tools.")
                .otherwise(defaultHelp));
        error.managedProperty().bind(error.visibleProperty());
    }

    @FXML
    private void apply() {
        var requested = preset.getValue() == Preset.RESTRICTED_INTERVIEWER
                ? SessionSettings.restrictedInterviewer(model.getValue(), voice.getValue())
                : new SessionSettings(model.getValue(), voice.getValue(), mode.getValue(),
                        instructions.getText(), turnDetection.getValue(), toolPreset.getValue());
        if (apply.test(requested)) {
            dialog.close();
        } else {
            error.setVisible(true);
        }
    }

    @FXML
    private void cancel() {
        dialog.close();
    }
}

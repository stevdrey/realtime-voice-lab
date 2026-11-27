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
        turnDetection.getItems().setAll(TurnDetection.values());
        turnDetection.setValue(current.turnDetection());
        turnDetection.disableProperty().bind(mode.valueProperty().isEqualTo(Mode.TEXT));
        toolPreset.getItems().setAll(ToolPreset.values());
        toolPreset.setValue(current.toolPreset());
        toolPreset.setDisable(connected);
        preset.getItems().setAll(Preset.values());
        instructions.setText(current.instructions());
        instructions.setTextFormatter(TextInputLimit.formatter(SessionSettings.MAX_INSTRUCTION_CHARS));
        for (var option : Preset.values()) {
            if (option.instructions().equals(current.instructions())) {
                preset.setValue(option);
                break;
            }
        }
        preset.valueProperty().addListener((observable, previous, selected) -> {
            if (selected != null) {
                instructions.setText(selected.instructions());
            }
        });
        instructions.textProperty().addListener((observable, previous, edited) -> {
            var selected = preset.getValue();
            if (selected != null && !selected.instructions().equals(edited)) {
                preset.setValue(null);
            }
        });
        applyButton.setText(connected ? "Apply" : "Save");
        reconnectHelp.setText(connected
                ? settings.voiceLocked()
                    ? "Disconnect and reconnect to change the model, voice, or tool preset."
                    : "Disconnect and reconnect to change the model or tool preset."
                : "Saved settings apply when you connect.");
        error.managedProperty().bind(error.visibleProperty());
    }

    @FXML
    private void apply() {
        var requested = new SessionSettings(model.getValue(), voice.getValue(), mode.getValue(),
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

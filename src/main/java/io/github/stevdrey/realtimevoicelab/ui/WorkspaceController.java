package io.github.stevdrey.realtimevoicelab.ui;

import javafx.beans.binding.Bindings;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.StackPane;

public final class WorkspaceController {
    @FXML
    private ComboBox<String> modelSelector;

    @FXML
    private StackPane settingsControl;

    @FXML
    private Button settingsButton;

    @FXML
    private StackPane micControl;

    @FXML
    private Button micButton;

    @FXML
    private StackPane connectControl;

    @FXML
    private Button connectButton;

    @FXML
    private void initialize() {
        modelSelector.getSelectionModel().selectFirst();
        modelSelector.accessibleTextProperty().bind(Bindings.createStringBinding(
                () -> "Model: " + modelSelector.getValue(), modelSelector.valueProperty()));

        installUnavailableTooltip(settingsControl, settingsButton);
        installUnavailableTooltip(micControl, micButton);
        installUnavailableTooltip(connectControl, connectButton);
    }

    private void installUnavailableTooltip(Node target, Button button) {
        // Disabled buttons do not receive hover events, so the tooltip goes on the button's hoverable wrapper.
        Tooltip.install(target, new Tooltip(button.getAccessibleText() + " (unavailable)"));
    }
}

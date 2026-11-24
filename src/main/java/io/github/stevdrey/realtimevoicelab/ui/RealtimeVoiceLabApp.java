package io.github.stevdrey.realtimevoicelab.ui;

import java.io.IOException;

import io.github.stevdrey.realtimevoicelab.audio.MicrophoneCapture;
import io.github.stevdrey.realtimevoicelab.realtime.JdkRealtimeTransport;
import io.github.stevdrey.realtimevoicelab.realtime.RealtimeTransport;
import javafx.application.Application;
import javafx.beans.value.ObservableBooleanValue;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

public final class RealtimeVoiceLabApp extends Application {
    private WorkspaceController workspace;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) throws IOException {
        start(stage, new JdkRealtimeTransport());
    }

    void start(Stage stage, RealtimeTransport transport) throws IOException {
        start(stage, transport, new MicrophoneCapture());
    }

    void start(Stage stage, RealtimeTransport transport, MicrophoneCapture microphone) throws IOException {
        start(stage, transport, microphone, stage.focusedProperty());
    }

    void start(Stage stage, RealtimeTransport transport, MicrophoneCapture microphone,
               ObservableBooleanValue windowFocus) throws IOException {
        var loader = new FXMLLoader(getClass().getResource("workspace.fxml"));
        workspace = new WorkspaceController(transport, microphone);
        loader.setController(workspace);
        Scene scene;
        try {
            scene = new Scene(loader.load(), 1100, 720);
            scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        } catch (IOException | RuntimeException error) {
            workspace.dispose();
            workspace = null;
            throw error;
        }

        stage.setTitle("Realtime Voice Lab");
        stage.setMinWidth(760);
        stage.setMinHeight(520);
        stage.setScene(scene);
        workspace.watchFocus(windowFocus);
        stage.setOnHidden(event -> stop());
        stage.show();
    }

    @Override
    public void stop() {
        if (workspace != null) {
            workspace.dispose();
        }
    }
}

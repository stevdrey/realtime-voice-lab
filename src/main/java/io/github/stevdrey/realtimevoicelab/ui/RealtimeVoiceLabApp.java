package io.github.stevdrey.realtimevoicelab.ui;

import java.io.IOException;
import java.nio.file.Path;

import io.github.stevdrey.realtimevoicelab.audio.MicrophoneCapture;
import io.github.stevdrey.realtimevoicelab.audio.SpeakerPlayback;
import io.github.stevdrey.realtimevoicelab.realtime.JdkRealtimeTransport;
import io.github.stevdrey.realtimevoicelab.realtime.RealtimeTransport;
import io.github.stevdrey.realtimevoicelab.ui.theme.ThemeProfileStore;
import io.github.stevdrey.realtimevoicelab.ui.theme.ThemeTokens;
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
        start(stage, transport, microphone, new SpeakerPlayback(), windowFocus);
    }

    void start(Stage stage, RealtimeTransport transport, MicrophoneCapture microphone, SpeakerPlayback speaker,
               ObservableBooleanValue windowFocus) throws IOException {
        var profiles = new ThemeProfileStore(Path.of(System.getProperty("user.home"), ".realtime-voice-lab", "themes"));
        start(stage, transport, microphone, speaker, windowFocus, profiles);
    }

    void start(Stage stage, RealtimeTransport transport, MicrophoneCapture microphone, SpeakerPlayback speaker,
               ObservableBooleanValue windowFocus, ThemeProfileStore profiles) throws IOException {
        var loader = new FXMLLoader(getClass().getResource("workspace.fxml"));
        var theme = new WorkspaceTheme();
        workspace = new WorkspaceController(transport, microphone, speaker, profiles, theme);
        loader.setController(workspace);
        Scene scene;
        try {
            scene = new Scene(loader.load(), 1100, 720);
            scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
            theme.apply(scene, ThemeTokens.defaults());
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

package io.github.stevdrey.realtimevoicelab.ui;

import java.net.http.WebSocket;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import io.github.stevdrey.realtimevoicelab.audio.FakeMicrophone;
import io.github.stevdrey.realtimevoicelab.audio.FakeSpeaker;
import io.github.stevdrey.realtimevoicelab.realtime.FakeTransport;
import io.github.stevdrey.realtimevoicelab.realtime.RealtimeSession;
import io.github.stevdrey.realtimevoicelab.realtime.RealtimeSession.TranscriptionModel;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.css.PseudoClass;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.robot.Robot;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class RealtimeVoiceLabAppTest {
    @BeforeAll
    static void startToolkit() throws InterruptedException {
        // The toolkit is shared by every FX test class in this JVM and ends with it, so it is never exited here.
        var ready = new CountDownLatch(1);
        Runnable keepRunning = () -> {
            Platform.setImplicitExit(false);
            ready.countDown();
        };
        try {
            Platform.startup(keepRunning);
        } catch (IllegalStateException alreadyStarted) {
            Platform.runLater(keepRunning);
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS), "JavaFX toolkit did not start");
    }

    @Test
    void showsTooltipsWhenHoveringSessionButtons() throws Exception {
        var stage = onFxThread(Stage::new);
        try {
            onFxThread(() -> {
                new RealtimeVoiceLabApp().start(stage);
                return null;
            });

            assertHoveredTooltip(stage, "#settings-button", "Settings (unavailable)");
            assertHoveredTooltip(stage, "#mic-button", "Microphone (connect to record)");
            assertHoveredTooltip(stage, "#connect-button", "Connect");
        } finally {
            onFxThread(() -> {
                stage.close();
                return null;
            });
        }
    }

    @Test
    void opensDisconnectedWithAccessibleDisabledControlsAndUnknownMetrics() throws Exception {
        withWindow(stage -> {
            assertTrue(stage.isShowing());
            assertEquals("Realtime Voice Lab", stage.getTitle());

            var scene = stage.getScene();
            var model = (ComboBox<?>) scene.lookup("#model-selector");
            assertEquals("gpt-realtime-2.1-mini", model.getValue());
            assertEquals("Model: gpt-realtime-2.1-mini", model.getAccessibleText());
            assertTrue(model.isDisabled());
            var transcription = (ComboBox<?>) scene.lookup("#transcription-selector");
            assertEquals(TranscriptionModel.OFF, transcription.getValue());
            assertFalse(transcription.isDisabled());
            assertDisabledButton(scene, "#settings-button", "Settings");
            assertDisabledButton(scene, "#mic-button", "Microphone");
            assertSession(stage, "Disconnected", "Connect", false);

            var metrics = scene.getRoot().lookupAll(".metric-value");
            assertEquals(3, metrics.size());
            for (var metric : metrics) {
                assertEquals("—", ((Label) metric).getText());
            }
            var events = (ListView<?>) scene.lookup("#event-list");
            assertTrue(events.getItems().isEmpty());
            var conversation = (ScrollPane) scene.lookup("#conversation-content");
            assertTrue(conversation.isFocusTraversable());

            var panels = (SplitPane) scene.lookup("#main-panels");
            var conversationHeading = (Label) panels.getItems().get(0).lookup(".panel-title");
            var inspectorHeading = (Label) panels.getItems().get(1).lookup(".panel-title");
            assertSame(conversation, conversationHeading.getLabelFor());
            assertSame(events, inspectorHeading.getLabelFor());
        });
    }

    @Test
    void showsVoiceTranscriptsAndStopsPlaybackWithoutBlockingTheWorkspace() throws Exception {
        var transport = new FakeTransport();
        var microphone = new FakeMicrophone();
        var allowStop = new CompletableFuture<Void>();
        var speaker = new FakeSpeaker(() -> {}, allowStop::join);
        var stage = onFxThread(Stage::new);
        try {
            onFxThread(() -> {
                new RealtimeVoiceLabApp().start(stage, transport, microphone.microphone(), speaker.playback(),
                        stage.focusedProperty());
                @SuppressWarnings("unchecked")
                var transcription = (ComboBox<TranscriptionModel>) stage.getScene().lookup("#transcription-selector");
                transcription.setValue(TranscriptionModel.TRANSCRIBE);
                connectButton(stage).fire();
                assertTrue(transcription.isDisabled());
                return null;
            });
            var socket = transport.attempts.getFirst();
            socket.ready(TranscriptionModel.TRANSCRIBE.modelId());

            for (int turn = 1; turn <= 2; turn++) {
                int number = turn;
                int previousWrites = socket.writes.size();
                onFxThread(() -> {
                    stage.getScene().lookup("#mic-button").requestFocus();
                    new Robot().keyPress(KeyCode.SPACE);
                    return null;
                });
                microphone.queueBlocks(4);
                awaitOnFxThread(() -> "Recording · 0.2 s".equals(micStatus(stage)), "Voice take did not fill");
                onFxThread(() -> {
                    new Robot().keyRelease(KeyCode.SPACE);
                    return null;
                });
                awaitOnFxThread(() -> socket.writes.size() == previousWrites + 2, "Voice take was not committed");
                sendVoiceReply(socket, turn, microphone.block(turn));
                awaitOnFxThread(() -> {
                    var level = (ProgressBar) stage.getScene().lookup("#mic-level");
                    return speaker.devices.size() == number && micStatus(stage).equals("Speaking…")
                            && level.isVisible() && level.getProgress() > 0;
                }, "Synthetic playback did not show a level");
                var device = speaker.devices.getLast();
                assertFalse(device.wroteOnFxThread);

                if (turn == 1) {
                    socket.text("""
                            {"type":"response.output_audio_transcript.delta","response_id":"reply-1","item_id":"assistant-1",
                            "output_index":0,"content_index":0,"delta":"Synthetic reply"}
                            """, true);
                    awaitOnFxThread(() -> showsTurnText(stage, "Synthetic reply"), "Assistant transcript did not stream");
                    socket.text("""
                            {"type":"response.output_audio_transcript.done","response_id":"reply-1","item_id":"assistant-1",
                            "output_index":0,"content_index":0,"transcript":"Synthetic reply."}
                            """, true);
                    socket.text("""
                            {"type":"response.done","response":{"id":"reply-1","status":"completed","output":[]}}
                            """, true);
                    socket.text("""
                            {"type":"conversation.item.input_audio_transcription.completed","item_id":"input-1",
                            "content_index":0,"transcript":"Synthetic question."}
                            """, true);
                    onFxThread(() -> {
                        assertTrue(showsTurnText(stage, "You · Approximate transcript"));
                        assertTrue(showsTurnText(stage, "Synthetic question."));
                        assertTrue(showsTurnText(stage, "Synthetic reply."));
                        var stop = (Button) stage.getScene().lookup("#cancel-text");
                        assertEquals("Stop playback", stop.getAccessibleText());
                        assertFalse(stop.isDisabled());
                        stop.fire();
                        assertEquals("Closing speaker…", micStatus(stage));
                        assertFalse(stage.getScene().lookup("#mic-button").isDisabled());
                        return null;
                    });
                    allowStop.complete(null);
                    awaitOnFxThread(() -> "Hold to talk".equals(micStatus(stage))
                                    && !stage.getScene().lookup("#mic-button").isDisabled(),
                            "Microphone did not become available after speaker cleanup");
                } else {
                    socket.text("""
                            {"type":"conversation.item.input_audio_transcription.failed","item_id":"input-2",
                            "content_index":0,"error":{"message":"private synthetic detail"}}
                            """, true);
                    onFxThread(() -> {
                        assertTrue(showsTurnText(stage, "Voice input · Transcription unavailable"));
                        assertFalse(showsTurnText(stage, "private synthetic detail"));
                        stage.close();
                        return null;
                    });
                }
                awaitOnFxThread(() -> device.flushed && device.closed, "Speaker was not flushed and closed");
            }
            assertTrue(socket.aborted);
        } finally {
            allowStop.complete(null);
            onFxThread(() -> {
                stage.close();
                return null;
            });
        }
    }

    @ParameterizedTest
    @EnumSource(value = KeyCode.class, names = {"SPACE", "ENTER"})
    void keepsKeyboardFocusAndRequiresAFreshPressAfterSpeakerCleanup(KeyCode key) throws Exception {
        var transport = new FakeTransport();
        var microphone = new FakeMicrophone();
        var releaseFirstStop = new CompletableFuture<Void>();
        var releaseSecondStop = new CompletableFuture<Void>();
        var pendingStops = new ArrayDeque<>(List.of(releaseFirstStop, releaseSecondStop));
        var speaker = new FakeSpeaker(() -> {}, () -> pendingStops.removeFirst().join());
        var stage = onFxThread(Stage::new);
        try {
            onFxThread(() -> {
                new RealtimeVoiceLabApp().start(stage, transport, microphone.microphone(), speaker.playback(),
                        stage.focusedProperty());
                connectButton(stage).fire();
                stage.requestFocus();
                return null;
            });
            var socket = transport.attempts.getFirst();
            socket.ready();
            awaitOnFxThread(stage::isFocused, "Workspace never received window focus");
            var mic = onFxThread(() -> {
                var button = stage.getScene().lookup("#mic-button");
                button.requestFocus();
                assertTrue(button.isFocused());
                return button;
            });

            recordWithKey(stage, microphone, key);
            awaitOnFxThread(() -> socket.writes.size() == 3, "First key release did not commit the take");
            finishSyntheticPlayback(stage, socket, speaker, microphone, 1);
            int writesBeforeCleanup = socket.writes.size();
            var waitingForSpeaker = PseudoClass.getPseudoClass("waiting-for-speaker");

            onFxThread(() -> {
                assertTrue(mic.isFocused(), "Speaker cleanup moved keyboard focus away from the microphone");
                assertFalse(mic.isDisabled());
                assertTrue(mic.getPseudoClassStates().contains(waitingForSpeaker));
                assertEquals("Closing speaker…", mic.getAccessibleHelp());
                new Robot().keyPress(key);
                repeatFocusedKey(stage, key);
                return null;
            });
            awaitOnFxThread(() -> "Audio unavailable. Waiting for the speaker to close.".equals(micStatus(stage)),
                    "The speaker cleanup timeout did not appear");
            onFxThread(() -> {
                assertTrue(mic.isFocused());
                assertTrue(mic.getPseudoClassStates().contains(waitingForSpeaker));
                assertEquals(micStatus(stage), mic.getAccessibleHelp());
                repeatFocusedKey(stage, key);
                assertSession(stage, "Connected", "Disconnect", false);
                assertEquals(1, microphone.opens.get());
                assertEquals(writesBeforeCleanup, socket.writes.size());
                return null;
            });

            releaseFirstStop.complete(null);
            awaitOnFxThread(() -> "Hold to talk".equals(micStatus(stage)), "Speaker cleanup did not release the microphone");
            onFxThread(() -> {
                assertTrue(mic.isFocused());
                assertFalse(mic.getPseudoClassStates().contains(waitingForSpeaker));
                repeatFocusedKey(stage, key);
                assertEquals("Hold to talk", micStatus(stage));
                assertEquals(1, microphone.opens.get());
                assertEquals(writesBeforeCleanup, socket.writes.size());
                new Robot().keyRelease(key);
                return null;
            });

            recordWithKey(stage, microphone, key);
            awaitOnFxThread(() -> socket.writes.size() == writesBeforeCleanup + 2,
                    "The fresh key press did not submit exactly one take");
            assertEquals(2, microphone.opens.get());
            assertTrue(socket.writes.get(writesBeforeCleanup).contains("input_audio_buffer.append"));
            assertTrue(socket.writes.get(writesBeforeCleanup + 1).contains("input_audio_buffer.commit"));
            finishSyntheticPlayback(stage, socket, speaker, microphone, 2);

            var composer = onFxThread(() -> {
                assertTrue(mic.isFocused());
                var field = stage.getScene().lookup("#text-composer");
                field.requestFocus();
                assertTrue(field.isFocused());
                return field;
            });
            releaseSecondStop.complete(null);
            awaitOnFxThread(() -> "Hold to talk".equals(micStatus(stage)), "Second cleanup did not finish");
            onFxThread(() -> {
                assertTrue(composer.isFocused(), "Speaker cleanup stole focus from the composer");
                assertSession(stage, "Connected", "Disconnect", false);
                return null;
            });
            assertEquals(0, socket.closeWrites);
            assertFalse(socket.aborted);
        } finally {
            releaseFirstStop.complete(null);
            releaseSecondStop.complete(null);
            onFxThread(() -> {
                new Robot().keyRelease(key);
                stage.close();
                return null;
            });
        }
    }

    private void recordWithKey(Stage stage, FakeMicrophone microphone, KeyCode key) throws Exception {
        onFxThread(() -> {
            new Robot().keyPress(key);
            return null;
        });
        microphone.queueBlocks(4);
        awaitOnFxThread(() -> "Recording · 0.2 s".equals(micStatus(stage)), "Key press did not start recording");
        onFxThread(() -> {
            new Robot().keyRelease(key);
            return null;
        });
    }

    private void repeatFocusedKey(Stage stage, KeyCode key) {
        Event.fireEvent(stage.getScene().getFocusOwner(),
                new KeyEvent(KeyEvent.KEY_PRESSED, "", "", key, false, false, false, false));
    }

    private void finishSyntheticPlayback(Stage stage, FakeTransport.Socket socket, FakeSpeaker speaker,
                                         FakeMicrophone microphone, int turn) throws Exception {
        var pcm = microphone.block(turn);
        sendVoiceReply(socket, turn, pcm);
        awaitOnFxThread(() -> speaker.devices.size() == turn && speaker.devices.getLast().written().length == pcm.length,
                "The synthetic reply did not reach the speaker");
        socket.text("""
                {"type":"response.done","response":{"id":"reply-%d","status":"completed","output":[]}}
                """.formatted(turn), true);
        speaker.devices.getLast().playBufferedFrames();
        awaitOnFxThread(() -> "Closing speaker…".equals(micStatus(stage)), "Speaker cleanup did not become visible");
    }

    private void sendVoiceReply(FakeTransport.Socket socket, int turn, byte[] pcm) {
        socket.text("""
                {"type":"input_audio_buffer.committed","item_id":"input-%d"}
                """.formatted(turn), true);
        socket.text("""
                {"type":"response.created","response":{"id":"reply-%1$d","metadata":{"rvl_turn":"rvl-%1$d"}}}
                """.formatted(turn), true);
        socket.text("""
                {"type":"response.output_audio.delta","response_id":"reply-%d","item_id":"assistant-%d",
                 "output_index":0,"content_index":0,"delta":"%s"}
                """.formatted(turn, turn, Base64.getEncoder().encodeToString(pcm)), true);
    }

    private boolean showsTurnText(Stage stage, String expected) {
        return stage.getScene().lookup("#conversation-turns").lookupAll(".label").stream()
                .anyMatch(label -> expected.equals(((Label) label).getText()));
    }

    @Test
    void sendsStreamsFinalizesAndCancelsFromTheDesktopControls() throws Exception {
        var transport = new FakeTransport();
        var stage = onFxThread(Stage::new);
        try {
            onFxThread(() -> {
                new RealtimeVoiceLabApp().start(stage, transport);
                var scene = stage.getScene();
                assertTrue(scene.lookup("#text-composer").isDisabled());
                assertTrue(scene.lookup("#send-text").isDisabled());
                assertTrue(scene.lookup("#cancel-text").isDisabled());
                connectButton(stage).fire();
                assertTrue(scene.lookup("#send-text").isDisabled());
                return null;
            });
            var socket = transport.attempts.getLast();
            socket.ready();
            onFxThread(() -> {
                var scene = stage.getScene();
                var composer = (TextField) scene.lookup("#text-composer");
                var send = (Button) scene.lookup("#send-text");
                var cancel = (Button) scene.lookup("#cancel-text");
                assertFalse(composer.isDisabled());
                assertTrue(send.isDisabled());
                composer.insertText(0, "x".repeat(RealtimeSession.MAX_INPUT_CHARS + 500));
                assertEquals(RealtimeSession.MAX_INPUT_CHARS, composer.getText().length());
                assertEquals(RealtimeSession.MAX_INPUT_CHARS, composer.getCaretPosition());
                composer.clear();
                var emoji = "😀";
                var oneUnitLeft = "x".repeat(RealtimeSession.MAX_INPUT_CHARS - 1);
                composer.insertText(0, oneUnitLeft + emoji);
                assertEquals(oneUnitLeft, composer.getText());
                assertEquals(oneUnitLeft.length(), composer.getCaretPosition());

                var exactFit = "x".repeat(RealtimeSession.MAX_INPUT_CHARS - emoji.length()) + emoji;
                composer.selectAll();
                composer.replaceSelection(exactFit);
                assertEquals(exactFit, composer.getText());
                assertEquals(exactFit.length(), composer.getCaretPosition());

                composer.selectRange(1, 2);
                composer.replaceSelection(emoji);
                assertEquals(exactFit.substring(0, 1) + exactFit.substring(2), composer.getText());
                assertEquals(1, composer.getCaretPosition());

                composer.clear();
                composer.setText("Offline greeting");
                assertFalse(send.isDisabled());
                send.fire();
                assertEquals("", composer.getText());
                assertTrue(send.isDisabled());
                assertFalse(cancel.isDisabled());
                composer.setText("Offline follow-up");
                assertTrue(send.isDisabled());
                return null;
            });
            socket.text("""
                    {"type":"conversation.item.added","item":{"id":"rvl-1","type":"message","role":"user"}}
                    """, true);
            socket.text("""
                    {"type":"response.created","response":{"id":"reply-1","metadata":{"rvl_turn":"rvl-1"}}}
                    """, true);
            socket.text("""
                    {"type":"response.output_text.delta","response_id":"reply-1","item_id":"assistant-1",
                     "output_index":0,"content_index":0,"delta":"Partial"}
                    """, true);
            onFxThread(() -> {
                assertEquals("Partial", ((Label) stage.getScene().lookup(".assistant-text")).getText());
                return null;
            });
            socket.text("""
                    {"type":"response.output_text.delta","response_id":"reply-1","item_id":"assistant-1",
                     "output_index":0,"content_index":0,"delta":"%s"}
                    """.formatted(" A long synthetic answer for the scrolling check.".repeat(160)), true);
            onFxThread(() -> {
                var scene = stage.getScene();
                scene.getRoot().applyCss();
                scene.getRoot().layout();
                var conversation = (ScrollPane) scene.lookup("#conversation-content");
                assertTrue(conversation.getContent().getLayoutBounds().getHeight()
                        > conversation.getViewportBounds().getHeight());
                assertEquals(conversation.getVmax(), conversation.getVvalue(), 0.01);
                conversation.setVvalue(conversation.getVmin());
                return null;
            });
            socket.text("""
                    {"type":"response.output_text.delta","response_id":"reply-1","item_id":"assistant-1",
                     "output_index":0,"content_index":0,"delta":"%s"}
                    """.formatted(" More synthetic text.".repeat(40)), true);
            onFxThread(() -> {
                var conversation = (ScrollPane) stage.getScene().lookup("#conversation-content");
                assertEquals(conversation.getVmin(), conversation.getVvalue(), 0.01);
                return null;
            });
            socket.text("""
                    {"type":"response.done","response":{"id":"reply-1","status":"completed","output":[
                     {"type":"message","role":"assistant","content":[{"type":"output_text","text":"Final reply"}]}]}}
                    """, true);
            onFxThread(() -> {
                var scene = stage.getScene();
                var send = (Button) scene.lookup("#send-text");
                var cancel = (Button) scene.lookup("#cancel-text");
                assertEquals("Final reply", ((Label) scene.lookup(".assistant-text")).getText());
                assertEquals(1, scene.getRoot().lookupAll(".conversation-turn").size());
                assertFalse(send.isDisabled());
                assertTrue(cancel.isDisabled());
                send.fire();
                return null;
            });
            socket.text("""
                    {"type":"conversation.item.added","item":{"id":"rvl-2","type":"message","role":"user"}}
                    """, true);
            socket.text("""
                    {"type":"response.created","response":{"id":"reply-2","metadata":{"rvl_turn":"rvl-2"}}}
                    """, true);
            onFxThread(() -> {
                var cancel = (Button) stage.getScene().lookup("#cancel-text");
                assertFalse(cancel.isDisabled());
                cancel.fire();
                assertTrue(cancel.isDisabled());
                return null;
            });
            assertTrue(socket.writes.getLast().contains("\"type\":\"response.cancel\""));
            socket.text("""
                    {"type":"response.done","response":{"id":"reply-2","status":"cancelled","output":[]}}
                    """, true);
            onFxThread(() -> {
                var scene = stage.getScene();
                assertEquals(2, scene.getRoot().lookupAll(".conversation-turn").size());
                assertTrue(scene.getRoot().lookupAll(".conversation-turn .caption").stream()
                        .anyMatch(node -> "Response cancelled".equals(((Label) node).getText())));
                ((TextField) scene.lookup("#text-composer")).setText("Offline rejected turn");
                assertFalse(scene.lookup("#send-text").isDisabled());
                ((Button) scene.lookup("#send-text")).fire();
                return null;
            });
            socket.text("""
                    {"type":"error","error":{"event_id":"rvl-3-item","message":"private provider detail"}}
                    """, true);
            onFxThread(() -> {
                var scene = stage.getScene();
                assertTrue(scene.getRoot().lookupAll(".conversation-turn .caption").stream()
                        .anyMatch(node -> "Request failed".equals(((Label) node).getText())));
                assertFalse(scene.getRoot().lookupAll(".label").stream()
                        .anyMatch(node -> ((Label) node).getText().contains("private provider detail")));
                ((TextField) scene.lookup("#text-composer")).setText("Retry");
                assertFalse(scene.lookup("#send-text").isDisabled());
                connectButton(stage).fire();
                assertTrue(scene.lookup("#text-composer").isDisabled());
                return null;
            });
            socket.peerClose(WebSocket.NORMAL_CLOSURE, "");
        } finally {
            onFxThread(() -> {
                stage.close();
                return null;
            });
        }
        assertTrue(transport.closed);
    }

    @Test
    void connectsCancelsAndDisconnectsThroughTheWorkspaceUsingSyntheticEvents() throws Exception {
        var transport = new FakeTransport();
        var stage = onFxThread(Stage::new);
        try {
            onFxThread(() -> {
                new RealtimeVoiceLabApp().start(stage, transport);
                assertTrue(transport.attempts.isEmpty());
                assertSession(stage, "Disconnected", "Connect", false);
                connectButton(stage).fire();
                assertSession(stage, "Connecting…", "Cancel connection", false);
                connectButton(stage).fire();
                assertSession(stage, "Disconnected", "Connect", false);
                assertTrue(transport.attempts.getFirst().opening.isCancelled());
                connectButton(stage).fire();
                return null;
            });

            var cancelled = transport.attempts.getFirst();
            var current = transport.attempts.getLast();
            // Drive the listener from the test thread to exercise the handoff to JavaFX.
            cancelled.opened();
            assertTrue(cancelled.aborted);
            current.ready();
            onFxThread(() -> {
                assertSession(stage, "Connected", "Disconnect", false);
                assertFalse(stage.getScene().lookup("#mic-button").isDisabled());
                connectButton(stage).fire();
                assertSession(stage, "Disconnecting…", "Disconnecting…", true);
                return null;
            });

            assertEquals(1, current.closeWrites);
            current.peerClose(WebSocket.NORMAL_CLOSURE, "private synthetic detail");
            onFxThread(() -> {
                assertSession(stage, "Disconnected", "Connect", false);
                connectButton(stage).fire();
                return null;
            });

            var failed = transport.attempts.getLast();
            failed.opened();
            failed.text("""
                    {"type":"error","error":{"message":"private synthetic detail"}}
                    """, true);
            assertTrue(failed.aborted);
            onFxThread(() -> {
                assertSession(stage, "Realtime session failed", "Connect", false);
                connectButton(stage).fire();
                return null;
            });

            var capped = transport.attempts.getLast();
            capped.ready();
            for (int i = 0; i < 11; i++) {
                capped.text("""
                        {"type":"response.function_call_arguments.done","call_id":"test_call","arguments":"{}"}
                        """, true);
            }
            assertEquals(1, capped.closeWrites);
            capped.peerClose(WebSocket.NORMAL_CLOSURE, "private synthetic detail");
            onFxThread(() -> {
                assertSession(stage, "Session tool-call limit reached", "Connect", false);
                assertSame(capped, transport.attempts.getLast());
                connectButton(stage).fire();
                assertSession(stage, "Connecting…", "Cancel connection", false);
                return null;
            });
        } finally {
            onFxThread(() -> {
                stage.close();
                return null;
            });
        }
        assertTrue(transport.closed);
        assertTrue(transport.attempts.getLast().opening.isCancelled());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void staleCancelOrDisconnectCannotStartANewSession(boolean ready) throws Exception {
        var transport = new FakeTransport();
        var stage = onFxThread(Stage::new);
        try {
            onFxThread(() -> {
                new RealtimeVoiceLabApp().start(stage, transport);
                connectButton(stage).fire();
                return null;
            });

            var socket = transport.attempts.getFirst();
            if (ready) {
                socket.ready();
            }
            onFxThread(() -> {
                var action = ready ? "Disconnect" : "Cancel connection";
                // Deliver the remote close before the queued FX render can update the button.
                socket.peerClose(WebSocket.NORMAL_CLOSURE, "");
                assertEquals(action, connectButton(stage).getAccessibleText());
                connectButton(stage).fire();
                assertEquals(1, transport.attempts.size());
                assertEquals("Connect", connectButton(stage).getAccessibleText());
                return null;
            });
        } finally {
            onFxThread(() -> {
                stage.close();
                return null;
            });
        }
    }

    @Test
    void keepsBothDarkPanelsAndControlsVisibleAtMinimumSize() throws Exception {
        var stage = onFxThread(Stage::new);
        try {
            onFxThread(() -> {
                new RealtimeVoiceLabApp().start(stage);
                assertEquals(760, stage.getMinWidth());
                assertEquals(520, stage.getMinHeight());

                var scene = stage.getScene();
                assertEquals(1100, scene.getWidth());
                assertEquals(720, scene.getHeight());
                var panels = (SplitPane) scene.lookup("#main-panels");
                assertEquals(0.5, panels.getDividerPositions()[0], 0.01);

                stage.setWidth(stage.getMinWidth());
                stage.setHeight(stage.getMinHeight());
                return null;
            });
            awaitOnFxThread(() -> stage.getScene().getWidth() == 760 && stage.getScene().getHeight() == 520,
                    "The scene did not shrink to the minimum stage size");
            onFxThread(() -> {
                assertMinimumSizeLayout(stage);
                return null;
            });
        } finally {
            onFxThread(() -> {
                stage.close();
                return null;
            });
        }
    }

    @Test
    void holdsToRecordWithMouseAndKeyboardAndClosesAudioOnCancellationOrWindowClose() throws Exception {
        var transport = new FakeTransport();
        var microphone = new FakeMicrophone();
        var stage = onFxThread(Stage::new);
        try {
            onFxThread(() -> {
                new RealtimeVoiceLabApp().start(stage, transport, microphone.microphone());
                connectButton(stage).fire();
                return null;
            });
            var socket = transport.attempts.getFirst();
            socket.ready();
            onFxThread(() -> {
                var scene = stage.getScene();
                var mic = scene.lookup("#mic-button");
                assertFalse(mic.isDisabled());
                assertEquals(0, microphone.opens.get());
                var bounds = mic.localToScreen(mic.getBoundsInLocal());
                var robot = new Robot();
                robot.mouseMove(bounds.getCenterX(), bounds.getCenterY());
                robot.mousePress(MouseButton.PRIMARY);
                return null;
            });
            microphone.queueBlocks(4);
            awaitOnFxThread(() -> hasRecordingLevel(stage), "Mouse press did not show a recording level");
            assertEquals(1, socket.writes.size());
            onFxThread(() -> {
                var scene = stage.getScene();
                var footer = scene.lookup("#metrics");
                var bounds = footer.localToScreen(footer.getBoundsInLocal());
                var robot = new Robot();
                robot.mouseMove(bounds.getCenterX(), bounds.getCenterY());
                robot.mouseRelease(MouseButton.PRIMARY);
                return null;
            });
            awaitOnFxThread(() -> socket.writes.size() == 3 && !stage.getScene().lookup("#mic-level").isVisible(),
                    "Mouse release outside the button did not stop recording and commit");
            assertEquals(1, microphone.closes.get());
            socket.text("""
                    {"type":"input_audio_buffer.committed","item_id":"desktop-voice-item","event_id":"synthetic"}
                    """, true);
            socket.text("""
                    {"type":"response.done","response":{"id":"desktop-voice-reply","status":"completed",
                     "metadata":{"rvl_turn":"rvl-1"}}}
                    """, true);

            onFxThread(() -> {
                stage.getScene().lookup("#mic-button").requestFocus();
                new Robot().keyPress(KeyCode.SPACE);
                return null;
            });
            microphone.queueBlocks(3);
            awaitOnFxThread(() -> hasRecordingLevel(stage), "Space did not start recording");
            onFxThread(() -> {
                var robot = new Robot();
                robot.keyType(KeyCode.ESCAPE);
                robot.keyRelease(KeyCode.SPACE);
                return null;
            });
            awaitOnFxThread(() -> microphone.closes.get() == 2 && !stage.getScene().lookup("#mic-button").isDisabled(),
                    "Escape did not close the input line");
            assertEquals(4, socket.writes.size());

            onFxThread(() -> {
                // Keyboard focus must stay on the microphone after the take ends.
                assertTrue(stage.getScene().lookup("#mic-button").isFocused());
                new Robot().keyPress(KeyCode.SPACE);
                return null;
            });
            microphone.queueBlocks(3);
            awaitOnFxThread(() -> hasRecordingLevel(stage), "Second Space press did not start recording");
            onFxThread(() -> {
                stage.getScene().lookup("#text-composer").requestFocus();
                new Robot().keyRelease(KeyCode.SPACE);
                return null;
            });
            awaitOnFxThread(() -> microphone.closes.get() == 3 && !stage.getScene().lookup("#mic-level").isVisible(),
                    "Focus loss did not close the input line");
            assertEquals(4, socket.writes.size());

            onFxThread(() -> {
                stage.getScene().lookup("#mic-button").requestFocus();
                new Robot().keyPress(KeyCode.ENTER);
                return null;
            });
            microphone.queueBlocks(3);
            awaitOnFxThread(() -> hasRecordingLevel(stage), "Enter did not start recording");
            onFxThread(() -> {
                stage.close();
                new Robot().keyRelease(KeyCode.ENTER);
                return null;
            });
            awaitOnFxThread(() -> microphone.closes.get() == 4, "Window close did not release the input line");
            assertTrue(socket.aborted);
            assertEquals(4, socket.writes.size());
        } finally {
            onFxThread(() -> {
                stage.close();
                return null;
            });
        }
    }

    @ParameterizedTest
    @EnumSource(value = KeyCode.class, names = {"SPACE", "ENTER"})
    void mouseCanRecordAfterLosingWindowFocusWithAKeyHeld(KeyCode key) throws Exception {
        var transport = new FakeTransport();
        var microphone = new FakeMicrophone();
        var stage = onFxThread(Stage::new);
        // The headless window manager does not send a blur to the previous stage on a focus request.
        var windowFocus = new SimpleBooleanProperty(true);
        try {
            onFxThread(() -> {
                new RealtimeVoiceLabApp().start(stage, transport, microphone.microphone(), windowFocus);
                connectButton(stage).fire();
                stage.requestFocus();
                return null;
            });
            var socket = transport.attempts.getFirst();
            socket.ready();
            awaitOnFxThread(stage::isFocused, "Workspace never received window focus");
            onFxThread(() -> {
                var mic = stage.getScene().lookup("#mic-button");
                mic.requestFocus();
                Event.fireEvent(mic, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", key, false, false, false, false));
                return null;
            });
            microphone.queueBlocks(3);
            awaitOnFxThread(() -> hasRecordingLevel(stage), "Keyboard hold did not start recording");

            onFxThread(() -> {
                windowFocus.set(false);
                return null;
            });
            awaitOnFxThread(() -> microphone.closes.get() == 1 && "Hold to talk".equals(micStatus(stage)),
                    "Window blur did not cancel the key take");
            assertEquals(1, socket.writes.size());

            onFxThread(() -> {
                windowFocus.set(true);
                var mic = stage.getScene().lookup("#mic-button");
                Event.fireEvent(mic, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", key, false, false, false, false));
                Event.fireEvent(mic, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", key, false, false, false, false));
                assertEquals("Hold to talk", micStatus(stage));
                windowFocus.set(false);
                return null;
            });
            assertEquals(1, microphone.opens.get());
            assertEquals(1, socket.writes.size());

            onFxThread(() -> {
                var otherScene = new Scene(new Region());
                Event.fireEvent(otherScene.getRoot(),
                        new KeyEvent(KeyEvent.KEY_RELEASED, "", "", key, false, false, false, false));
                windowFocus.set(true);
                var mic = stage.getScene().lookup("#mic-button");
                Event.fireEvent(mic, new MouseEvent(MouseEvent.MOUSE_PRESSED, 0, 0, 0, 0, MouseButton.PRIMARY, 1,
                        false, false, false, false, true, false, false, false, false, true, null));
                return null;
            });
            microphone.queueBlocks(4);
            awaitOnFxThread(() -> "Recording · 0.2 s".equals(micStatus(stage)),
                    "Stale key prevented a new mouse capture");
            assertEquals(2, microphone.opens.get());

            onFxThread(() -> {
                var mic = stage.getScene().lookup("#mic-button");
                Event.fireEvent(mic,
                        new KeyEvent(KeyEvent.KEY_RELEASED, "", "", key, false, false, false, false));
                assertTrue(hasRecordingLevel(stage), "A delayed key release ended the mouse take");
                assertEquals(1, socket.writes.size());
                Event.fireEvent(mic, new MouseEvent(MouseEvent.MOUSE_RELEASED, 0, 0, 0, 0, MouseButton.PRIMARY, 1,
                        false, false, false, false, false, false, false, false, false, true, null));
                assertFalse(hasRecordingLevel(stage), "The mouse release did not end the mouse take");
                return null;
            });
            awaitOnFxThread(() -> socket.writes.size() == 3 && microphone.closes.get() == 2,
                    "Only the mouse release should submit the second take");
            assertTrue(socket.writes.get(1).contains("input_audio_buffer.append"));
            assertTrue(socket.writes.get(2).contains("input_audio_buffer.commit"));
        } finally {
            onFxThread(() -> {
                stage.close();
                return null;
            });
        }
    }

    private boolean hasRecordingLevel(Stage stage) {
        var scene = stage.getScene();
        var level = (ProgressBar) scene.lookup("#mic-level");
        return micStatus(stage).startsWith("Recording") && level.isVisible() && level.getProgress() > 0;
    }

    private String micStatus(Stage stage) {
        return ((Label) stage.getScene().lookup("#mic-status")).getText();
    }

    private void assertMinimumSizeLayout(Stage stage) {
        var scene = stage.getScene();
        var root = scene.getRoot();
        var panels = (SplitPane) scene.lookup("#main-panels");
        panels.setDividerPositions(0.42);
        root.layout();

        assertEquals(0.42, panels.getDividerPositions()[0], 0.01);
        var conversation = (Region) panels.getItems().get(0);
        var inspector = (Region) panels.getItems().get(1);
        assertTrue(conversation.getWidth() >= 260);
        assertTrue(inspector.getWidth() >= 260);
        assertTrue(conversation.getHeight() > 100);
        assertEquals(Color.web("#1e2430"), conversation.getBackground().getFills().getFirst().getFill());
        var baseline = (Region) scene.lookup(".waveform-baseline");
        assertTrue(baseline.getBoundsInParent().getWidth() > 0);
        var dotsWidth = baseline.getShape().getBoundsInLocal().getWidth();
        assertTrue(dotsWidth > 0);
        assertTrue(dotsWidth <= baseline.getWidth(), "Unscaled waveform dots overflow the baseline");

        var metrics = scene.lookup("#metrics");
        var footerBounds = metrics.localToScene(metrics.getBoundsInLocal());
        assertTrue(footerBounds.getMaxY() <= scene.getHeight());
        var transcription = scene.lookup("#transcription-selector");
        var selectorBounds = transcription.localToScene(transcription.getBoundsInLocal());
        assertTrue(selectorBounds.getMinX() >= 0 && selectorBounds.getMaxX() <= scene.getWidth());
    }

    private void assertDisabledButton(Scene scene, String selector, String name) {
        var button = (Button) scene.lookup(selector);
        assertTrue(button.isDisabled());
        assertEquals(name, button.getAccessibleText());
    }

    private Button connectButton(Stage stage) {
        return (Button) stage.getScene().lookup("#connect-button");
    }

    private void assertSession(Stage stage, String text, String action, boolean disabled) {
        assertEquals(text, ((Label) stage.getScene().lookup("#session-status")).getText());
        assertEquals(action, connectButton(stage).getAccessibleText());
        assertEquals(disabled, connectButton(stage).isDisabled());
    }

    private void assertHoveredTooltip(Stage stage, String selector, String expectedText) throws Exception {
        onFxThread(() -> {
            var button = stage.getScene().lookup(selector);
            var bounds = button.localToScreen(button.getBoundsInLocal());
            new Robot().mouseMove(bounds.getCenterX(), bounds.getCenterY());
            return null;
        });

        awaitOnFxThread(() -> Window.getWindows().stream().anyMatch(window ->
                        window instanceof Tooltip tooltip && expectedText.equals(tooltip.getText())),
                "Hovering over " + selector + " did not show: " + expectedText);
    }

    private void awaitOnFxThread(Callable<Boolean> condition, String failureMessage) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (onFxThread(condition)) {
                return;
            }
            Thread.sleep(25);
        }
        fail(failureMessage);
    }

    private void withWindow(Consumer<Stage> checks) throws Exception {
        var stage = onFxThread(Stage::new);
        try {
            onFxThread(() -> {
                new RealtimeVoiceLabApp().start(stage);
                checks.accept(stage);
                return null;
            });
        } finally {
            // A separate task still queues the close when the checks fail or time out.
            onFxThread(() -> {
                stage.close();
                return null;
            });
        }
    }

    private <T> T onFxThread(Callable<T> action) throws Exception {
        var run = new FutureTask<>(action);
        Platform.runLater(run);
        try {
            return run.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException failure) {
            // Report assertion failures and exceptions from the FX thread as themselves.
            switch (failure.getCause()) {
                case Error error -> throw error;
                case Exception exception -> throw exception;
                case null, default -> throw failure;
            }
        } catch (TimeoutException timeout) {
            run.cancel(true);
            throw timeout;
        }
    }
}

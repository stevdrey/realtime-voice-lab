package io.github.stevdrey.realtimevoicelab.ui;

import java.net.http.WebSocket;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import io.github.stevdrey.realtimevoicelab.realtime.FakeTransport;
import io.github.stevdrey.realtimevoicelab.realtime.RealtimeSession;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.robot.Robot;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
            assertHoveredTooltip(stage, "#mic-button", "Microphone (unavailable)");
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
                        .anyMatch(node -> "Text request failed".equals(((Label) node).getText())));
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
                assertTrue(((Button) stage.getScene().lookup("#mic-button")).isDisabled());
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

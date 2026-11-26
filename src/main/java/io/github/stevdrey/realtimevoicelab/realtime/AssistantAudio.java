package io.github.stevdrey.realtimevoicelab.realtime;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.stevdrey.realtimevoicelab.audio.MicrophoneCapture;

/** Maps the speaker's ordered PCM stream back to assistant message items. */
final class AssistantAudio {
    private static final int MAX_SPANS = 256;
    private final List<Span> spans = new ArrayList<>();
    private final LinkedHashMap<String, Integer> receivedFrames = new LinkedHashMap<>();
    private int totalFrames;

    void receive(String itemId, int contentIndex, int frames) throws IOException {
        // The GA truncate event supports content index zero.
        if (contentIndex != 0) {
            throw new IOException("Unsupported Realtime audio content index");
        }
        addItem(itemId);
        if (!spans.isEmpty() && spans.getLast().itemId.equals(itemId)) {
            spans.getLast().frames += frames;
        } else {
            if (spans.size() == MAX_SPANS) {
                throw new IOException("Too many Realtime audio spans");
            }
            spans.add(new Span(itemId, totalFrames, frames));
        }
        totalFrames += frames;
        receivedFrames.merge(itemId, frames, Integer::sum);
    }

    void reconcile(JsonNode response, SessionProtocol protocol) throws IOException {
        for (var item : response.path("output")) {
            if (!"message".equals(item.path("type").textValue())
                    || !"assistant".equals(item.path("role").textValue())) {
                continue;
            }
            var content = item.path("content");
            for (int index = 0; index < content.size(); index++) {
                if ("output_audio".equals(content.get(index).path("type").textValue())) {
                    if (index != 0) {
                        throw new IOException("Unsupported Realtime audio content index");
                    }
                    addItem(protocol.requiredId(item.path("id").textValue()));
                }
            }
        }
    }

    ArrayDeque<Cut> cuts(long playedFrames, boolean interruptedGeneration) {
        var playedByItem = new LinkedHashMap<String, Integer>();
        for (var span : spans) {
            int played = (int) Math.clamp(playedFrames - span.firstFrame, 0, span.frames);
            playedByItem.merge(span.itemId, played, Integer::sum);
        }
        var cuts = new ArrayDeque<Cut>();
        receivedFrames.forEach((itemId, received) -> {
            int played = playedByItem.getOrDefault(itemId, 0);
            // Cancellation can leave generated audio that was never delivered as a delta.
            if (interruptedGeneration || received == 0 || played < received) {
                cuts.addLast(new Cut(itemId, (int) (played * 1000L / MicrophoneCapture.SAMPLE_RATE)));
            }
        });
        return cuts;
    }

    private void addItem(String itemId) throws IOException {
        if (receivedFrames.size() == MAX_SPANS && !receivedFrames.containsKey(itemId)) {
            throw new IOException("Too many Realtime audio items");
        }
        receivedFrames.putIfAbsent(itemId, 0);
    }

    record Cut(String itemId, int milliseconds) {
    }

    private static final class Span {
        private final String itemId;
        private final int firstFrame;
        private int frames;

        Span(String itemId, int firstFrame, int frames) {
            this.itemId = itemId;
            this.firstFrame = firstFrame;
            this.frames = frames;
        }
    }
}

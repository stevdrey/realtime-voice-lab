package io.github.stevdrey.realtimevoicelab.realtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/** Stores only the metadata that the inspector is allowed to display. Owned by the session monitor. */
public final class EventInspector {
    static final int CAPACITY = 256;
    private static final int MAX_PROVIDER_ID_CHARS = 512;
    private static final Set<String> SERVER_TYPES = Set.of(
            "error", "session.created", "session.updated", "rate_limits.updated",
            "conversation.created", "conversation.item.added", "conversation.item.done",
            "conversation.item.deleted", "conversation.item.retrieved", "conversation.item.truncated",
            "conversation.item.input_audio_transcription.delta", "conversation.item.input_audio_transcription.completed",
            "conversation.item.input_audio_transcription.failed", "conversation.item.input_audio_transcription.segment",
            "input_audio_buffer.cleared", "input_audio_buffer.committed",
            "input_audio_buffer.speech_started", "input_audio_buffer.speech_stopped", "input_audio_buffer.timeout_triggered",
            "response.created", "response.done", "response.output_item.added", "response.output_item.done",
            "response.content_part.added", "response.content_part.done",
            "response.output_text.delta", "response.output_text.done",
            "response.output_audio.delta", "response.output_audio.done",
            "response.output_audio_transcript.delta", "response.output_audio_transcript.done",
            "response.function_call_arguments.delta", "response.function_call_arguments.done");

    public enum Direction {
        INCOMING, OUTGOING
    }

    public record Entry(long sequence, Instant timestamp, Direction direction, String type, String eventId,
                        Integer audioBytes) {
        public String jsonDetail() {
            var detail = JsonNodeFactory.instance.objectNode()
                    .put("timestamp", timestamp.toString())
                    .put("direction", direction.name().toLowerCase(Locale.ROOT))
                    .put("type", type);
            if (eventId != null) {
                detail.put("event_id", eventId);
            }
            if (audioBytes != null) {
                detail.put("audio_bytes", audioBytes);
            }
            return detail.toPrettyString();
        }
    }

    private final Clock clock;
    private final byte[] aliasSalt = UUID.randomUUID().toString().getBytes(StandardCharsets.US_ASCII);
    private final MessageDigest digest;
    private final ArrayDeque<Entry> entries = new ArrayDeque<>(CAPACITY);
    private List<Entry> snapshot = List.of();
    private long sequence;

    EventInspector(Clock clock) {
        this.clock = clock;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    void incoming(String type, String eventId, Integer audioBytes) {
        // A new provider field or event name cannot introduce free text into the history.
        append(Direction.INCOMING, SERVER_TYPES.contains(type) ? type : "Unknown event",
                providerAlias(eventId), audioBytes);
    }

    void invalidIncoming() {
        // Oversized or unparseable frames keep a fixed label and no ID.
        append(Direction.INCOMING, "Invalid event", null, null);
    }

    void outgoing(String type, String eventId, Integer audioBytes) {
        append(Direction.OUTGOING, type, eventId, audioBytes);
    }

    private String providerAlias(String eventId) {
        if (eventId == null || eventId.length() > MAX_PROVIDER_ID_CHARS || eventId.isBlank()) {
            return null;
        }
        // Provider event IDs are opaque strings. An app-local alias preserves correlation.
        digest.update(aliasSalt);
        var hash = digest.digest(eventId.getBytes(StandardCharsets.UTF_8));
        return "server-" + HexFormat.of().formatHex(hash, 0, 12);
    }

    private void append(Direction direction, String type, String eventId, Integer audioBytes) {
        if (entries.size() == CAPACITY) {
            entries.removeFirst();
        }
        entries.addLast(new Entry(++sequence, clock.instant(), direction, type, eventId, audioBytes));
        snapshot = null;
    }

    List<Entry> snapshot() {
        if (snapshot == null) {
            snapshot = List.copyOf(entries);
        }
        return snapshot;
    }

    void clear() {
        entries.clear();
        snapshot = List.of();
    }
}

package io.github.stevdrey.realtimevoicelab.realtime;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.stevdrey.realtimevoicelab.realtime.RealtimeSession.RateLimit;
import io.github.stevdrey.realtimevoicelab.realtime.RealtimeSession.UsageView;
import io.github.stevdrey.realtimevoicelab.realtime.SessionSettings.Model;

final class SessionUsage {
    // Keep IDs for the entire session so a replay cannot become billable after history eviction.
    private static final int MAX_TRACKED_RESPONSES = 4096;
    private static final int MAX_TRACKED_RATE_UPDATES = 8192;

    private final Model model;
    private final SessionPricing.Rates rates;
    private final Set<String> pending = new HashSet<>();
    private final Set<String> finished = new HashSet<>();
    private final Set<String> rateUpdateIds = new HashSet<>();
    private BigDecimal estimate = BigDecimal.ZERO;
    private long inputTokens;
    private long outputTokens;
    private long cachedTokens;
    private Long reasoningTokens;
    private boolean unboundRequest;
    private boolean missingUsage;
    private final boolean transcriptionEnabled;
    private boolean transcriptionUsed;
    private List<RateLimit> rateLimits = List.of();

    SessionUsage(Model model, SessionPricing.Rates rates, boolean transcriptionEnabled) {
        this.model = model;
        this.rates = rates;
        this.transcriptionEnabled = transcriptionEnabled;
    }

    void requestSent() {
        unboundRequest = true;
    }

    void requestRejected() {
        unboundRequest = false;
    }

    void responseCreated(String id, boolean matchesRequest) throws IOException {
        if (finished.contains(id)) {
            return;
        }
        if (!pending.contains(id)) {
            checkCapacity();
            pending.add(id);
        }
        if (matchesRequest) {
            unboundRequest = false;
        }
    }

    boolean isFinished(String id) {
        return finished.contains(id);
    }

    boolean responseDone(String id, JsonNode data, boolean matchesRequest) throws IOException {
        if (finished.contains(id)) {
            return false;
        }
        if (!pending.remove(id)) {
            checkCapacity();
        }
        if (matchesRequest) {
            unboundRequest = false;
        }
        finished.add(id);
        var usage = ResponseUsage.read(data);
        if (usage == null) {
            missingUsage = true;
            return true;
        }
        inputTokens += usage.textInput() + usage.audioInput();
        outputTokens += usage.textOutput() + usage.audioOutput();
        cachedTokens += usage.cachedText() + usage.cachedAudio();
        if (usage.reasoning() != null) {
            reasoningTokens = (reasoningTokens == null ? 0 : reasoningTokens) + usage.reasoning();
        }
        if (rates != null) {
            estimate = estimate.add(rates.estimate(usage));
        }
        return true;
    }

    private void checkCapacity() throws IOException {
        if (pending.size() + finished.size() >= MAX_TRACKED_RESPONSES) {
            missingUsage = true;
            throw new IOException("Too many Realtime responses to track");
        }
    }

    boolean hasPending() {
        return unboundRequest || !pending.isEmpty();
    }

    boolean missingUsage() {
        return missingUsage;
    }

    boolean reachedCutoff(BigDecimal cutoff) {
        return cutoff != null && estimate.compareTo(cutoff) >= 0;
    }

    void close() {
        missingUsage |= hasPending();
        unboundRequest = false;
        pending.clear();
    }

    boolean audioSubmitted() {
        if (!transcriptionEnabled || transcriptionUsed) {
            return false;
        }
        transcriptionUsed = true;
        return true;
    }

    void updateRateLimits(String eventId, JsonNode limits) {
        if (rateUpdateIds.size() == MAX_TRACKED_RATE_UPDATES) {
            return;
        }
        if (eventId != null && eventId.length() <= SessionProtocol.MAX_ID_CHARS && !eventId.isBlank()
                && !rateUpdateIds.add(eventId)) {
            return;
        }
        // Retain one snapshot of documented quotas. Reserved quota is independent of reported usage.
        rateLimits = limits.isArray() ? readRateLimits(limits) : List.of();
    }

    private List<RateLimit> readRateLimits(JsonNode limits) {
        var result = new ArrayList<RateLimit>(2);
        for (var entry : limits) {
            var name = entry.path("name").textValue();
            if (!"requests".equals(name) && !"tokens".equals(name)) {
                continue;
            }
            if (result.stream().anyMatch(limit -> limit.name().equals(name))) {
                return List.of();
            }
            long limit = ResponseUsage.count(entry, "limit");
            long remaining = ResponseUsage.count(entry, "remaining");
            var reset = entry.path("reset_seconds");
            double seconds = reset.doubleValue();
            if (limit >= 0 && remaining >= 0 && remaining <= limit
                    && reset.isNumber() && Double.isFinite(seconds) && seconds >= 0 && seconds <= Integer.MAX_VALUE) {
                result.add(new RateLimit(name, limit, remaining, seconds));
            }
        }
        return List.copyOf(result);
    }

    UsageView view() {
        var amount = finished.isEmpty() || missingUsage || rates == null ? null : estimate;
        return new UsageView(model, amount, rates == null ? null : rates.date(), inputTokens, outputTokens,
                cachedTokens, reasoningTokens, hasPending(), missingUsage, transcriptionEnabled,
                transcriptionUsed, rateLimits);
    }
}

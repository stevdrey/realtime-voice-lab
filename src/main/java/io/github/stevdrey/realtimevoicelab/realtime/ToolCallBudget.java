package io.github.stevdrey.realtimevoicelab.realtime;

import java.util.HashSet;
import java.util.Set;

/** Charges early completion signals and terminal-only calls to the same session budget. */
final class ToolCallBudget {
    private final Set<ReportedCall> reported = new HashSet<>();
    private final Set<String> claimed = new HashSet<>();
    private int count;

    boolean recordSignal(String responseId, String callId) {
        if (!takeSlot()) {
            return false;
        }
        if (responseId != null) {
            reported.add(new ReportedCall(responseId, callId));
        }
        return true;
    }

    boolean reserve(String responseId, String callId) {
        // A missing or invalid call ID cannot correlate two events for quota accounting.
        return (callId != null && reported.remove(new ReportedCall(responseId, callId))) || takeSlot();
    }

    boolean hasUnmatchedSignal(String responseId) {
        return reported.stream().anyMatch(call -> responseId.equals(call.responseId()));
    }

    boolean claim(String callId) {
        return claimed.add(callId);
    }

    private boolean takeSlot() {
        return ++count <= RealtimeSession.MAX_TOOL_CALLS;
    }

    private record ReportedCall(String responseId, String callId) {
    }
}

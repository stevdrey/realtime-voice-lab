package io.github.stevdrey.realtimevoicelab.realtime;

import com.fasterxml.jackson.databind.JsonNode;

/** The priced buckets are disjoint. Reasoning is informational and is included in text output. */
record ResponseUsage(long textInput, long audioInput, long cachedText, long cachedAudio,
                     long textOutput, long audioOutput, Long reasoning) {
    private static final long MISSING = -1;
    private static final long INVALID = -2;

    static ResponseUsage read(JsonNode usage) {
        var input = usage.path("input_token_details");
        var output = usage.path("output_token_details");
        var cached = input.path("cached_tokens_details");
        long inputTotal = count(usage, "input_tokens");
        long outputTotal = count(usage, "output_tokens");
        long total = count(usage, "total_tokens");
        long reasoning = count(output, "reasoning_tokens");
        if (inputTotal < 0 || outputTotal < 0 || total != MISSING && total != inputTotal + outputTotal
                || reasoning == INVALID || !optionalObject(input) || !optionalObject(output)
                || !optionalObject(cached) || !absentOrZero(input, "image_tokens")
                || !absentOrZero(cached, "image_tokens") || !absentOrZero(output, "image_tokens")) {
            return null;
        }

        long textIn = count(input, "text_tokens");
        long audioIn = count(input, "audio_tokens");
        long textOut = count(output, "text_tokens");
        long audioOut = count(output, "audio_tokens");
        // A total can confirm omitted zero buckets. A positive unexplained remainder is ambiguous.
        if (!sumsTo(inputTotal, textIn, audioIn)) {
            return null;
        }
        if (!sumsTo(outputTotal, textOut, audioOut)) {
            // Some payloads may report reasoning separately in the breakdown while including it in output_tokens.
            if (reasoning <= 0 || !sumsTo(outputTotal - reasoning, textOut, audioOut)) {
                return null;
            }
            textOut = Math.max(0, textOut) + reasoning;
        }
        if (reasoning > Math.max(0, textOut)) {
            return null;
        }

        textIn = Math.max(0, textIn);
        audioIn = Math.max(0, audioIn);
        long cachedTotal = count(input, "cached_tokens");
        long cachedText = count(cached, "text_tokens");
        long cachedAudio = count(cached, "audio_tokens");
        if (cachedText == INVALID || cachedAudio == INVALID || cachedTotal == INVALID
                || cachedText > textIn || cachedAudio > audioIn || cachedTotal > inputTotal) {
            return null;
        }
        if (cachedTotal == MISSING) {
            // A missing cache breakdown is only unambiguous if all potentially cached buckets are known.
            cachedTotal = knownOrZero(cachedText, textIn) + knownOrZero(cachedAudio, audioIn);
            if (cachedTotal < 0) {
                return null;
            }
        }
        long minimumCachedText = cachedText >= 0 ? cachedText : Math.max(0, cachedTotal - audioIn);
        long maximumCachedText = cachedText >= 0 ? cachedText : Math.min(textIn, cachedTotal);
        if (cachedAudio >= 0) {
            minimumCachedText = Math.max(minimumCachedText, cachedTotal - cachedAudio);
            maximumCachedText = Math.min(maximumCachedText, cachedTotal - cachedAudio);
        }
        if (minimumCachedText != maximumCachedText || minimumCachedText > cachedTotal
                || cachedTotal - minimumCachedText > audioIn) {
            return null;
        }
        return new ResponseUsage(textIn, audioIn, minimumCachedText, cachedTotal - minimumCachedText,
                Math.max(0, textOut), Math.max(0, audioOut), reasoning == MISSING ? null : reasoning);
    }

    private static boolean optionalObject(JsonNode node) {
        return node.isMissingNode() || node.isObject();
    }

    private static boolean absentOrZero(JsonNode node, String field) {
        long value = count(node, field);
        return value == MISSING || value == 0;
    }

    private static boolean sumsTo(long total, long text, long audio) {
        return text != INVALID && audio != INVALID && Math.max(0, text) + Math.max(0, audio) == total;
    }

    private static long knownOrZero(long cached, long tokens) {
        return cached >= 0 || tokens == 0 ? Math.max(0, cached) : INVALID;
    }

    static long count(JsonNode node, String field) {
        var value = node.path(field);
        if (value.isMissingNode()) {
            return MISSING;
        }
        return value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= 0
                ? value.intValue() : INVALID;
    }
}

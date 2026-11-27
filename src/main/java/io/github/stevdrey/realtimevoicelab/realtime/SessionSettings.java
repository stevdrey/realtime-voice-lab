package io.github.stevdrey.realtimevoicelab.realtime;

import java.util.Objects;

public record SessionSettings(Model model, Voice voice, Mode mode, String instructions, TurnDetection turnDetection,
                              ToolPreset toolPreset) {
    public static final int MAX_INSTRUCTION_CHARS = 4_000;
    public static final SessionSettings DEFAULT = new SessionSettings(
            Model.MINI, Voice.MARIN, Mode.AUDIO, Preset.FREE_CONVERSATION.instructions());

    public SessionSettings(Model model, Voice voice, Mode mode, String instructions) {
        this(model, voice, mode, instructions, TurnDetection.MANUAL);
    }

    public SessionSettings(Model model, Voice voice, Mode mode, String instructions, TurnDetection turnDetection) {
        this(model, voice, mode, instructions, turnDetection, ToolPreset.DEMO_COUNTER);
    }

    public SessionSettings {
        Objects.requireNonNull(model);
        Objects.requireNonNull(voice);
        Objects.requireNonNull(mode);
        Objects.requireNonNull(instructions);
        Objects.requireNonNull(turnDetection);
        Objects.requireNonNull(toolPreset);
        if (instructions.length() > MAX_INSTRUCTION_CHARS) {
            throw new IllegalArgumentException("Instructions exceed the session limit");
        }
    }

    public enum Model {
        MINI("gpt-realtime-2.1-mini"), STANDARD("gpt-realtime-2.1");

        private final String id;

        Model(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        @Override
        public String toString() {
            return id;
        }
    }

    public enum Voice {
        MARIN("marin"), CEDAR("cedar");

        private final String id;

        Voice(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        @Override
        public String toString() {
            return id;
        }
    }

    public enum Mode {
        AUDIO("Audio", "audio"), TEXT("Text", "text");

        private final String label;
        private final String modality;

        Mode(String label, String modality) {
            this.label = label;
            this.modality = modality;
        }

        String modality() {
            return modality;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public TurnDetection effectiveTurnDetection() {
        return mode == Mode.AUDIO ? turnDetection : TurnDetection.MANUAL;
    }

    public enum TurnDetection {
        MANUAL("Push to talk", null), SERVER_VAD("Server VAD", "server_vad"),
        SEMANTIC_VAD("Semantic VAD", "semantic_vad");

        private final String label;
        private final String type;

        TurnDetection(String label, String type) {
            this.label = label;
            this.type = type;
        }

        public boolean automatic() {
            return type != null;
        }

        String type() {
            return type;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum Preset {
        FREE_CONVERSATION("Free Conversation", "Respond briefly."),
        ENGLISH_COACH("English Coach", """
                Help the user practice conversational English. Reply in English and adapt to their level.
                Offer one brief, encouraging correction when useful, then ask one natural follow-up question.""");

        private final String label;
        private final String instructions;

        Preset(String label, String instructions) {
            this.label = label;
            this.instructions = instructions;
        }

        public String instructions() {
            return instructions;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum ToolPreset {
        DEMO_COUNTER("Demo counter"), TOY_WORKSPACE("Toy workspace");

        private final String label;

        ToolPreset(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    @Override
    public String toString() {
        return "SessionSettings[model=" + model + ", voice=" + voice + ", mode=" + mode + "]";
    }
}

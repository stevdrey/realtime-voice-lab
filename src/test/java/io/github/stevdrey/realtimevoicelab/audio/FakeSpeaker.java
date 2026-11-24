package io.github.stevdrey.realtimevoicelab.audio;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.SourceDataLine;

public final class FakeSpeaker {
    public static final int BUFFER_BYTES = MicrophoneCapture.BLOCK_BYTES;
    public final List<Device> devices = new CopyOnWriteArrayList<>();
    private final Runnable beforeOpen;
    private final Runnable beforeStop;

    public FakeSpeaker() {
        this(() -> {});
    }

    public FakeSpeaker(Runnable beforeOpen) {
        this(beforeOpen, () -> {});
    }

    public FakeSpeaker(Runnable beforeOpen, Runnable beforeStop) {
        this.beforeOpen = beforeOpen;
        this.beforeStop = beforeStop;
    }

    public SpeakerPlayback playback() {
        return new SpeakerPlayback(() -> {
            beforeOpen.run();
            var device = new Device(beforeStop);
            devices.add(device);
            return device.line();
        });
    }

    public static final class Device {
        private final ByteArrayOutputStream written = new ByteArrayOutputStream();
        private final Runnable beforeStop;
        private long framesPlayed;
        private boolean started;
        public volatile boolean stopped;
        public volatile boolean flushed;
        public volatile boolean closed;
        public volatile boolean wroteOnFxThread;

        private Device(Runnable beforeStop) {
            this.beforeStop = beforeStop;
        }

        private SourceDataLine line() {
            return (SourceDataLine) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{SourceDataLine.class}, (line, method, args) -> switch (method.getName()) {
                        case "getFormat" -> new AudioFormat(24_000, 16, 1, true, false);
                        case "getBufferSize" -> BUFFER_BYTES;
                        case "getLongFramePosition" -> position();
                        case "available" -> available();
                        case "start" -> {
                            started = true;
                            yield null;
                        }
                        case "stop" -> {
                            beforeStop.run();
                            stopped = true;
                            yield null;
                        }
                        case "flush" -> {
                            flushed = true;
                            yield null;
                        }
                        case "close" -> {
                            closed = true;
                            yield null;
                        }
                        case "write" -> write((byte[]) args[0], (int) args[1], (int) args[2]);
                        default -> throw new AssertionError("Unexpected speaker operation " + method.getName());
                    });
        }

        private synchronized int write(byte[] pcm, int offset, int length) {
            if (!started || stopped || length <= 0 || length > available() || length % Short.BYTES != 0) {
                throw new AssertionError("Unexpected PCM write");
            }
            wroteOnFxThread |= javafx.application.Platform.isFxApplicationThread();
            written.write(pcm, offset, length);
            return length;
        }

        private synchronized int available() {
            return BUFFER_BYTES - written.size() + (int) (framesPlayed * Short.BYTES);
        }

        private synchronized long position() {
            return framesPlayed;
        }

        public synchronized byte[] written() {
            return written.toByteArray();
        }

        public synchronized void playBufferedFrames() {
            framesPlayed = written.size() / Short.BYTES;
        }
    }
}

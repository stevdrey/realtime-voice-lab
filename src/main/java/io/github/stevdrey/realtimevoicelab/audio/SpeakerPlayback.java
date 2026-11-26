package io.github.stevdrey.realtimevoicelab.audio;

import java.time.Duration;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.LongSupplier;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

public final class SpeakerPlayback {
    private static final AudioFormat FORMAT = new AudioFormat(MicrophoneCapture.SAMPLE_RATE, 16, 1, true, false);
    private static final int BLOCK_BYTES = MicrophoneCapture.BLOCK_BYTES;
    private static final long POLL_NANOS = Duration.ofMillis(5).toNanos();
    private static final long METER_INTERVAL_NANOS = Duration.ofMillis(40).toNanos();
    private static final long STALL_NANOS = Duration.ofSeconds(3).toNanos();

    public enum Result {
        COMPLETED, STOPPED, UNAVAILABLE
    }

    @FunctionalInterface
    public interface Source {
        SourceDataLine open() throws LineUnavailableException;
    }

    private final Source source;
    private final LongSupplier nanoTime;

    public SpeakerPlayback() {
        this(SpeakerPlayback::openDefaultLine);
    }

    public SpeakerPlayback(Source source) {
        this(source, System::nanoTime);
    }

    SpeakerPlayback(Source source, LongSupplier nanoTime) {
        this.source = source;
        this.nanoTime = nanoTime;
    }

    /** Owns the line on the playback worker. No device method uses the session or queue lock. */
    public Result play(Stream stream, Runnable started, DoubleConsumer meter, Consumer<Result> cleaningUp) {
        if (stream.stopped) {
            return Result.STOPPED;
        }
        try (var line = source.open()) {
            var result = Result.UNAVAILABLE;
            boolean startAttempted = false;
            var progress = new Progress();
            try {
                var actualFormat = line.getFormat();
                if (!FORMAT.matches(actualFormat) || !actualFormat.matches(FORMAT)
                        || line.getBufferSize() < Short.BYTES) {
                    return result;
                }
                if (stream.stopped) {
                    result = Result.STOPPED;
                    return result;
                }
                startAttempted = true;
                line.start();
                started.run();
                progress.firstFrame = line.getLongFramePosition();
                result = writeStream(stream, line, meter, progress);
                return result;
            } finally {
                try {
                    if (result != Result.COMPLETED) {
                        stream.stop();
                    }
                    cleaningUp.accept(result);
                } finally {
                    if (startAttempted) {
                        stopLine(line, stream, result, progress);
                    }
                }
            }
        } catch (LineUnavailableException | RuntimeException unavailable) {
            return Result.UNAVAILABLE;
        }
    }

    private void stopLine(SourceDataLine line, Stream stream, Result result, Progress progress) {
        try {
            line.stop();
            // Sample after stop, before flush removes the device's remaining buffered frames.
            stream.playedFrames = Math.clamp(line.getLongFramePosition() - progress.firstFrame, 0,
                    progress.writtenFrames);
        } finally {
            if (result != Result.COMPLETED || stream.stopped) {
                line.flush();
            }
        }
    }

    private Result writeStream(Stream stream, SourceDataLine line, DoubleConsumer meter, Progress progress) {
        var block = new byte[BLOCK_BYTES];
        long playedFrames = 0;
        long progressAt = nanoTime.getAsLong();
        long meterAt = progressAt - METER_INTERVAL_NANOS;
        while (!stream.stopped) {
            long now = nanoTime.getAsLong();
            long position = line.getLongFramePosition() - progress.firstFrame;
            if (position > playedFrames) {
                playedFrames = position;
                progressAt = now;
            }
            int available = Math.clamp(line.available(), 0, BLOCK_BYTES);
            int count = stream.read(block, available - available % Short.BYTES);
            if (count > 0 && !stream.stopped) {
                // Write only complete frames the line can accept without blocking for space.
                int written = line.write(block, 0, count);
                if (written > 0 && written <= count) {
                    progress.writtenFrames += written / Short.BYTES;
                }
                if (written != count) {
                    return Result.UNAVAILABLE;
                }
                progressAt = now;
                if (now - meterAt >= METER_INTERVAL_NANOS) {
                    meter.accept(level(block, written));
                    meterAt = now;
                }
                continue;
            }
            // Keep checking cancellation and device progress until the last written frame has played.
            if (playedFrames >= progress.writtenFrames && stream.isComplete()) {
                return Result.COMPLETED;
            }
            if (playedFrames >= progress.writtenFrames && stream.isEmpty()) {
                progressAt = now;
                if (now - meterAt >= METER_INTERVAL_NANOS) {
                    meter.accept(0);
                    meterAt = now;
                }
            } else if (now - progressAt >= STALL_NANOS) {
                return Result.UNAVAILABLE;
            }
            LockSupport.parkNanos(POLL_NANOS);
        }
        return Result.STOPPED;
    }

    private double level(byte[] pcm, int count) {
        int peak = 0;
        for (int index = 0; index < count; index += Short.BYTES) {
            int sample = (short) ((pcm[index] & 0xff) | (pcm[index + 1] << 8));
            peak = Math.max(peak, Math.abs(sample));
        }
        return peak / 32768.0;
    }

    private static SourceDataLine openDefaultLine() throws LineUnavailableException {
        var info = new DataLine.Info(SourceDataLine.class, FORMAT);
        if (!AudioSystem.isLineSupported(info)) {
            throw new IllegalArgumentException("Required PCM output format unavailable");
        }
        var line = AudioSystem.getSourceDataLine(FORMAT);
        try {
            line.open(FORMAT, 4 * BLOCK_BYTES);
            return line;
        } catch (LineUnavailableException | RuntimeException error) {
            line.close();
            throw error;
        }
    }

    private static final class Progress {
        private long firstFrame;
        private long writtenFrames;
    }

    /** Single producer and consumer, with a byte bound independent of the number of deltas. */
    public static final class Stream {
        private byte[] queue;
        private int head;
        private int size;
        private boolean complete;
        private volatile boolean stopped;
        // A stopped device position is published before the playback worker returns.
        private volatile long playedFrames;

        public long playedFrames() {
            return playedFrames;
        }

        public Stream(int capacity) {
            if (capacity <= 0 || capacity % Short.BYTES != 0) {
                throw new IllegalArgumentException("Playback capacity must hold complete PCM frames");
            }
            queue = new byte[capacity];
        }

        public synchronized boolean offer(byte[] pcm) {
            if (stopped || complete || pcm.length % Short.BYTES != 0 || pcm.length > queue.length - size) {
                return false;
            }
            int tail = (head + size) % queue.length;
            int first = Math.min(pcm.length, queue.length - tail);
            System.arraycopy(pcm, 0, queue, tail, first);
            System.arraycopy(pcm, first, queue, 0, pcm.length - first);
            size += pcm.length;
            return true;
        }

        public synchronized void complete() {
            complete = true;
        }

        public synchronized void stop() {
            stopped = true;
            size = 0;
            queue = null;
        }

        private synchronized int read(byte[] block, int limit) {
            if (stopped || size == 0 || limit == 0) {
                return 0;
            }
            int count = Math.min(Math.min(size, limit), queue.length - head);
            System.arraycopy(queue, head, block, 0, count);
            head = (head + count) % queue.length;
            size -= count;
            return count;
        }

        private synchronized boolean isComplete() {
            return complete && size == 0;
        }

        private synchronized boolean isEmpty() {
            return size == 0;
        }
    }
}

package io.github.stevdrey.realtimevoicelab.audio;

import java.time.Duration;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;

public final class MicrophoneCapture {
    public static final int SAMPLE_RATE = 24_000;
    public static final int BYTES_PER_SECOND = SAMPLE_RATE * Short.BYTES;
    public static final int BLOCK_BYTES = BYTES_PER_SECOND / 25;
    private static final AudioFormat FORMAT = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
    private static final long POLL_NANOS = Duration.ofMillis(5).toNanos();

    public enum Result {
        STOPPED, LIMIT, UNSUPPORTED, UNAVAILABLE
    }

    @FunctionalInterface
    public interface Source {
        TargetDataLine open() throws LineUnavailableException;
    }

    private final Source source;

    public MicrophoneCapture() {
        this(MicrophoneCapture::openDefaultLine);
    }

    public MicrophoneCapture(Source source) {
        this.source = source;
    }

    /** Runs on the capture worker. The same worker opens, starts, reads and closes the line. */
    public Result capture(Duration limit, BooleanSupplier stopped, Runnable started, BiConsumer<byte[], Integer> chunk) {
        if (stopped.getAsBoolean()) {
            return Result.STOPPED;
        }
        long startedAt = System.nanoTime();
        long limitNanos = limit.toNanos();
        try (var line = source.open()) {
            var actualFormat = line.getFormat();
            if (!FORMAT.matches(actualFormat) || !actualFormat.matches(FORMAT)) {
                return Result.UNSUPPORTED;
            }
            int bufferBytes = Math.min(BLOCK_BYTES, line.getBufferSize());
            int readBytes = bufferBytes - bufferBytes % Short.BYTES;
            if (readBytes <= 0) {
                return Result.UNAVAILABLE;
            }
            if (stopped.getAsBoolean()) {
                return Result.STOPPED;
            }
            if (System.nanoTime() - startedAt >= limitNanos) {
                return Result.LIMIT;
            }
            line.start();
            started.run();
            var block = new byte[readBytes];
            while (!stopped.getAsBoolean()) {
                if (System.nanoTime() - startedAt >= limitNanos) {
                    return Result.LIMIT;
                }
                if (line.available() < block.length) {
                    LockSupport.parkNanos(POLL_NANOS);
                    continue;
                }
                if (stopped.getAsBoolean()) {
                    break;
                }
                // Java Sound guarantees available bytes can be read without blocking.
                int count = line.read(block, 0, block.length);
                if (count <= 0 || count > block.length || count % Short.BYTES != 0) {
                    return Result.UNAVAILABLE;
                }
                chunk.accept(block, count);
            }
            return Result.STOPPED;
        } catch (IllegalArgumentException unsupported) {
            return Result.UNSUPPORTED;
        } catch (LineUnavailableException | IllegalStateException | SecurityException unavailable) {
            return Result.UNAVAILABLE;
        }
    }

    private static TargetDataLine openDefaultLine() throws LineUnavailableException {
        var info = new DataLine.Info(TargetDataLine.class, FORMAT);
        if (!AudioSystem.isLineSupported(info)) {
            throw new IllegalArgumentException("Required PCM input format unavailable");
        }
        var line = AudioSystem.getTargetDataLine(FORMAT);
        try {
            line.open(FORMAT, 4 * BLOCK_BYTES);
            return line;
        } catch (LineUnavailableException | RuntimeException error) {
            line.close();
            throw error;
        }
    }
}

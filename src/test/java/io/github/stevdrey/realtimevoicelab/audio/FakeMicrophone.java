package io.github.stevdrey.realtimevoicelab.audio;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.TargetDataLine;

public final class FakeMicrophone {
    public final AtomicInteger opens = new AtomicInteger();
    public final AtomicInteger starts = new AtomicInteger();
    public final AtomicInteger closes = new AtomicInteger();
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicInteger read = new AtomicInteger();
    private final AudioFormat format;
    private final Runnable beforeOpen;
    private final IntConsumer afterAvailable;
    private final IntConsumer beforeRead;

    public FakeMicrophone() {
        this(length -> {});
    }

    public FakeMicrophone(IntConsumer beforeRead) {
        this(bytes -> {}, beforeRead);
    }

    public FakeMicrophone(IntConsumer afterAvailable, IntConsumer beforeRead) {
        this(new AudioFormat(24_000, 16, 1, true, false), () -> {}, afterAvailable, beforeRead);
    }

    public FakeMicrophone(AudioFormat format, Runnable beforeOpen) {
        this(format, beforeOpen, bytes -> {}, length -> {});
    }

    private FakeMicrophone(AudioFormat format, Runnable beforeOpen, IntConsumer afterAvailable,
                           IntConsumer beforeRead) {
        this.format = format;
        this.beforeOpen = beforeOpen;
        this.afterAvailable = afterAvailable;
        this.beforeRead = beforeRead;
    }

    public MicrophoneCapture microphone() {
        return new MicrophoneCapture(() -> {
            opens.incrementAndGet();
            beforeOpen.run();
            return (TargetDataLine) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{TargetDataLine.class}, (line, method, args) -> switch (method.getName()) {
                        case "getFormat" -> format;
                        case "getBufferSize" -> 4 * MicrophoneCapture.BLOCK_BYTES;
                        case "start" -> {
                            starts.incrementAndGet();
                            yield null;
                        }
                        case "close" -> {
                            queued.set(0);
                            closes.incrementAndGet();
                            yield null;
                        }
                        case "available" -> {
                            int bytes = Math.min(queued.get(), MicrophoneCapture.BLOCK_BYTES);
                            afterAvailable.accept(bytes);
                            yield bytes;
                        }
                        case "read" -> readBlock((byte[]) args[0], (int) args[1], (int) args[2]);
                        default -> throw new AssertionError("Unexpected audio line operation " + method.getName());
                    });
        });
    }

    public void queueBlocks(int count) {
        queueBytes(count * MicrophoneCapture.BLOCK_BYTES);
    }

    public void queueBytes(int count) {
        queued.addAndGet(count);
    }

    private int readBlock(byte[] target, int offset, int length) {
        if (offset != 0 || length <= 0 || length > MicrophoneCapture.BLOCK_BYTES || length % Short.BYTES != 0
                || queued.getAndAdd(-length) < length) {
            throw new AssertionError("Unexpected microphone read");
        }
        beforeRead.accept(length);
        int start = read.getAndAdd(length);
        for (int index = 0; index < length; index += Short.BYTES) {
            target[index] = (byte) ((start + index) / MicrophoneCapture.BLOCK_BYTES);
            target[index + 1] = 32;
        }
        return length;
    }

    public byte[] block(int sequence) {
        var pcm = new byte[MicrophoneCapture.BLOCK_BYTES];
        for (int index = 0; index < pcm.length; index += Short.BYTES) {
            pcm[index] = (byte) sequence;
            pcm[index + 1] = 32;
        }
        return pcm;
    }
}

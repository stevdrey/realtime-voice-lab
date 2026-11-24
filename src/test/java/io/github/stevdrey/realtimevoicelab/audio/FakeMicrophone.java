package io.github.stevdrey.realtimevoicelab.audio;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

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

    public FakeMicrophone() {
        this(new AudioFormat(24_000, 16, 1, true, false), () -> {});
    }

    public FakeMicrophone(AudioFormat format, Runnable beforeOpen) {
        this.format = format;
        this.beforeOpen = beforeOpen;
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
                        case "available" -> queued.get() > 0 ? MicrophoneCapture.BLOCK_BYTES : 0;
                        case "read" -> readBlock((byte[]) args[0], (int) args[1], (int) args[2]);
                        default -> throw new AssertionError("Unexpected audio line operation " + method.getName());
                    });
        });
    }

    public void queueBlocks(int count) {
        queued.addAndGet(count);
    }

    private int readBlock(byte[] target, int offset, int length) {
        if (queued.getAndDecrement() <= 0 || offset != 0 || length != MicrophoneCapture.BLOCK_BYTES) {
            throw new AssertionError("Unexpected microphone read");
        }
        var block = block(read.getAndIncrement());
        System.arraycopy(block, 0, target, offset, length);
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

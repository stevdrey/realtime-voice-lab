package io.github.stevdrey.realtimevoicelab.audio;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sound.sampled.AudioFormat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MicrophoneCaptureTest {
    @Test
    void deviceOpenTimeDoesNotCountAgainstTheRecordingLimit() {
        var device = new FakeMicrophone(new AudioFormat(24_000, 16, 1, true, false), () -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        });
        device.queueBlocks(10);
        var starts = new AtomicInteger();
        var bytes = new AtomicInteger();

        var result = device.microphone().capture(Duration.ofMillis(50), () -> false, starts::incrementAndGet,
                (pcm, count) -> bytes.addAndGet(count));

        assertEquals(MicrophoneCapture.Result.LIMIT, result);
        assertEquals(1, starts.get());
        assertEquals(1, device.starts.get());
        assertTrue(bytes.get() > 0);
        assertEquals(1, device.closes.get());
    }
}

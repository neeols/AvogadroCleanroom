package io.github.wurtzitane.avogadro.client.export;

import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Reusable pixel memory pool for zero-allocation frame capture.
 * <p>
 * Exactly two owned frames across capture, its queue, quantizers and writer. Never waits.
 */
final class MediaPixelPool implements AutoCloseable {

    /** Array of fixed pixel pool slots. */
    private final Slot[] slots = {new Slot(), new Slot()};

    /** Image width and height dimensions in pixels. */
    private final int width, height;

    /** Flag indicating whether unpremultiplied alpha is used. */
    private final boolean unpremultiply;

    /** Flag tracking whether the pool has been closed. */
    private volatile boolean closed;

    /**
     * Creates a {@link MediaPixelPool} with specified dimensions and alpha settings.
     *
     * @param width image width in pixels
     * @param height image height in pixels
     * @param unpremultiply {@code true} for unpremultiplied alpha
     */
    MediaPixelPool(int width, int height, boolean unpremultiply) {
        this.width = width;
        this.height = height;
        this.unpremultiply = unpremultiply;
    }

    /**
     * Acquires an available pixel buffer slot from the pool.
     * <p>
     * Render-thread acquisition; workers only release. Exhaustion pauses capture for a tick.
     *
     * @return an acquired {@link MediaPixels}, or {@code null} if exhausted or closed
     */
    MediaPixels acquire() {
        if (closed) {
            return null;
        }
        for (Slot slot : slots) {
            if (!slot.inUse.compareAndSet(false, true)) {
                continue;
            }
            try {
                if (slot.pixels == null) {
                    slot.pixels = new MediaPixels(width, height, unpremultiply);
                }
                return slot.pixels;
            } catch (Throwable failure) {
                slot.inUse.set(false);
                throw failure;
            }
        }
        return null;
    }

    /**
     * Checks whether any pixel slot is currently available for acquisition.
     *
     * @return {@code true} if at least one slot is free, {@code false} otherwise
     */
    boolean available() {
        return !closed && (!slots[0].inUse.get() || !slots[1].inUse.get());
    }

    /**
     * Releases a pixel buffer back into the pool.
     *
     * @param pixels the {@link MediaPixels} to release
     * @throws IllegalStateException if the frame was already released
     * @throws IllegalArgumentException if the frame does not belong to this pool
     */
    void release(MediaPixels pixels) {
        for (Slot slot : slots) {
            if (slot.pixels == pixels) {
                if (!slot.inUse.compareAndSet(true, false)) {
                    throw new IllegalStateException("Export frame was already released");
                }
                if (closed) {
                    slot.pixels = null;
                }
                return;
            }
        }
        throw new IllegalArgumentException("Export frame does not belong to this capture");
    }

    /**
     * Polls and releases a queue of queued frames.
     *
     * @param frames queue of frames
     * @param pixels function mapping frames to their corresponding {@link MediaPixels}
     * @param <T> frame type parameter
     */
    <T> void releaseQueued(Queue<T> frames, Function<T, MediaPixels> pixels) {
        T frame;
        while ((frame = frames.poll()) != null) {
            release(pixels.apply(frame));
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        closed = true;
        for (Slot slot : slots) {
            if (!slot.inUse.get()) {
                slot.pixels = null;
            }
        }
        // A cancelled worker drops its final leased buffer when it releases ownership.
    }

    /**
     * Represents a single slot within the media pixel pool.
     */
    private static final class Slot {

        /** Atomic boolean flag tracking slot usage state. */
        final AtomicBoolean inUse = new AtomicBoolean();

        /** The cached media pixels instance. */
        volatile MediaPixels pixels;
    }
}

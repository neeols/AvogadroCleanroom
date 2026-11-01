package io.github.wurtzitane.avogadro.client.export;

import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * A streaming media encoder interface for exporting animated frame sequences.
 * <p>Call {@link #finish()} only after every frame has been written in order.
 */
public interface MediaEncoder extends AutoCloseable {

    /**
     * Writes a rendered frame image at the specified frame index.
     *
     * @param frame      the frame image to write
     * @param frameIndex the zero-based sequential frame index
     * @throws IOException if an I/O error occurs or frame order/dimensions are invalid
     */
    void write(BufferedImage frame, int frameIndex) throws IOException;

    /**
     * Finalizes the media encoding process and flushes output streams.
     *
     * @throws IOException if finalization fails or no frames were written
     */
    void finish() throws IOException;

    /**
     * Releases system resources and aborts an unfinished export.
     * <p>{@inheritDoc}
     */
    @Override
    void close();
}
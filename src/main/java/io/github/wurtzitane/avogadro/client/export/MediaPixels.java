package io.github.wurtzitane.avogadro.client.export;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.image.SinglePixelPackedSampleModel;
import java.nio.ByteBuffer;

/**
 * Represents owned bottom-up RGBA readback pixel buffers.
 * <p>Conversion happens only on an encoder worker.
 */
final class MediaPixels {

    /** Image width and height dimensions in pixels. */
    final int width, height;

    /** Raw byte array containing RGBA pixel data. */
    final byte[] rgba;

    /** Flag indicating whether alpha unpremultiplication is enabled. */
    final boolean unpremultiply;

    /**
     * Constructs a new {@link MediaPixels} buffer container with specified dimensions.
     *
     * @param width         image width in pixels
     * @param height        image height in pixels
     * @param unpremultiply {@code true} to unpremultiply alpha values
     */
    MediaPixels(int width, int height, boolean unpremultiply) {
        this.width = width;
        this.height = height;
        this.unpremultiply = unpremultiply;
        rgba = new byte[Math.multiplyExact(Math.multiplyExact(width, height), 4)];
    }

    /**
     * Copies pixel data from a direct {@link ByteBuffer} source into the raw RGBA buffer.
     *
     * @source source byte buffer
     */
    void copyFrom(ByteBuffer source) {
        source.get(0, rgba);
    }

    /**
     * Converts the raw pixel data into a standard ARGB {@link BufferedImage}.
     *
     * @param reusable optional reusable buffered image target
     * @return the populated buffered image
     */
    BufferedImage image(BufferedImage reusable) {
        BufferedImage result = reusable;
        if (result == null || result.getWidth() != width || result.getHeight() != height
                || packedPixels(result) == null) {
            result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        }
        int[] target = packedPixels(result);
        for (int y = 0; y < height; y++) {
            int source = (height - 1 - y) * width * 4;
            int destination = y * width;
            for (int x = 0; x < width; x++, source += 4) {
                target[destination + x] = argb(source);
            }
        }
        return result;
    }

    /**
     * Extracts an ARGB integer color from the source byte array index.
     * <p>Exact original readback rounding, without allocating an intermediate ARGB image.
     *
     * @param source byte index offset in the RGBA array
     * @return ARGB packed integer color
     */
    int argb(int source) {
        int alpha = rgba[source + 3] & 255;
        int red = rgba[source] & 255, green = rgba[source + 1] & 255, blue = rgba[source + 2] & 255;
        if (unpremultiply && alpha > 0 && alpha < 255) {
            red = Math.min(255, (red * 255 + alpha / 2) / alpha);
            green = Math.min(255, (green * 255 + alpha / 2) / alpha);
            blue = Math.min(255, (blue * 255 + alpha / 2) / alpha);
        }
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    /**
     * Reads a specific row of pixels into an integer target array.
     *
     * @param y      row index
     * @param target target integer array
     */
    void readRow(int y, int[] target) {
        int source = (height - 1 - y) * width * 4;
        for (int x = 0; x < width; x++, source += 4) {
            target[x] = argb(source);
        }
    }

    /**
     * Retrieves direct packed pixel integer data from a buffered image if compatible.
     * <p>Only a contiguous, nontranslated, unpremultiplied ARGB raster is safe for direct access.
     *
     * @param image source buffered image
     * @return backing integer data array, or {@code null} if incompatible
     */
    static int[] packedPixels(BufferedImage image) {
        if (image.getType() != BufferedImage.TYPE_INT_ARGB || image.isAlphaPremultiplied()
                || image.getRaster().getParent() != null || image.getRaster().getMinX() != 0
                || image.getRaster().getMinY() != 0 || image.getRaster().getSampleModelTranslateX() != 0
                || image.getRaster().getSampleModelTranslateY() != 0
                || !(image.getRaster().getSampleModel() instanceof SinglePixelPackedSampleModel model)
                || model.getScanlineStride() != image.getWidth()
                || !(image.getRaster().getDataBuffer() instanceof DataBufferInt data)
                || data.getOffset() != 0 || data.getNumBanks() != 1
                || data.getSize() != image.getWidth() * image.getHeight()) {
            return null;
        }
        return data.getData();
    }
}
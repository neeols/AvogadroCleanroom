package io.github.wurtzitane.avogadro.client.export;

import org.w3c.dom.Node;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.FileImageOutputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.IndexColorModel;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Iterator;

/**
 * Java's GIF writer with bounded, frame-local palette construction.
 */
public final class GifMediaEncoder implements MediaEncoder {

    /** Image metadata format string for standard Java GIF encoding. */
    private static final String IMAGE_METADATA = "javax_imageio_gif_image_1.0";

    /** Underlying ImageWriter instance used for encoding GIF sequences. */
    private final ImageWriter writer;

    /** Output stream writing encoded GIF data to disk. */
    private final ImageOutputStream output;

    /** Target frames per second. */
    private final int fps;

    /** Flag indicating whether transparency is enabled. */
    private final boolean transparent;

    /** Array of protected RGB colors that remain stable across frames. */
    private final int[] protectedColors;

    /** Reusable palette workspace. */
    private Palette workspace;

    /** Video width in pixels. */
    private int width;

    /** Video height in pixels. */
    private int height;

    /** Counter tracking the number of frames successfully written. */
    private int frames;

    /** Flag indicating whether encoding has finished. */
    private boolean finished;

    /** Flag indicating whether the encoder has been closed. */
    private volatile boolean closed;

    /**
     * Constructs a new {@link GifMediaEncoder} writing to a temporary file without protected colors.
     *
     * @param temporaryOutput path to the temporary output file
     * @param fps             frames per second
     * @param transparent     {@code true} to enable transparency
     * @throws IOException if encoder initialization fails
     */
    public GifMediaEncoder(Path temporaryOutput, int fps, boolean transparent) throws IOException {
        this(temporaryOutput, fps, transparent, new int[0]);
    }

    /**
     * Constructs a new {@link GifMediaEncoder} with optional protected UI colors.
     * <p>Exact UI colors stay stable while the remaining palette follows each rendered model frame.
     * RGB and ARGB inputs are treated as opaque; at most 192 distinct colors may be protected.
     *
     * @param temporaryOutput   path to the temporary output file
     * @param fps               frames per second (1–100)
     * @param transparent       {@code true} to enable transparency
     * @param protectedColors   array of RGB colors to protect from quantization shifting
     * @throws IOException              if encoder initialization fails
     * @throws IllegalArgumentException if frame rate is out of range or too many colors are protected
     */
    public GifMediaEncoder(Path temporaryOutput, int fps, boolean transparent, int[] protectedColors) throws IOException {
        if (fps < 1 || fps > 100) {
            throw new IllegalArgumentException("GIF frame rate must be 1–100");
        }
        this.protectedColors = normalizeProtectedColors(protectedColors);
        this.fps = fps;
        this.transparent = transparent;
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("gif");
        if (!writers.hasNext()) {
            throw new IOException("This Java installation has no GIF encoder");
        }
        writer = writers.next();
        ImageOutputStream stream = null;
        try {
            stream = new FileImageOutputStream(temporaryOutput.toFile());
            writer.setOutput(stream);
            writer.prepareWriteSequence(null);
            output = stream;
        } catch (IOException | RuntimeException failure) {
            writer.dispose();
            if (stream != null) {
                stream.close();
            }
            throw failure;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write(BufferedImage frame, int frameIndex) throws IOException {
        checkOpen();
        if (frameIndex != frames) {
            throw new IOException("GIF frames must be written in order");
        }
        if (frames == 0) {
            width = frame.getWidth();
            height = frame.getHeight();
        } else if (frame.getWidth() != width || frame.getHeight() != height) {
            throw new IOException("GIF frame dimensions changed");
        }
        if (workspace == null) {
            workspace = new Palette();
        }
        writeIndexed(workspace.quantize(frame, transparent, protectedColors, 256), frameIndex);
    }

    /**
     * Creates a new quantizer instance for background thread usage.
     * Each leased quantizer owns its raster until its ordered write completes.
     */
    Quantizer quantizer() {
        return new Quantizer(transparent, protectedColors);
    }

    /** Writes an already-quantized, indexed image frame to the GIF sequence. */
    void writeIndexed(BufferedImage indexed, int frameIndex) throws IOException {
        long measured = ExportMetrics.start();
        checkOpen();
        if (frameIndex != frames) {
            throw new IOException("GIF frames must be written in order");
        }
        if (frames == 0) {
            width = indexed.getWidth();
            height = indexed.getHeight();
        } else if (indexed.getWidth() != width || indexed.getHeight() != height) {
            throw new IOException("GIF frame dimensions changed");
        }
        IIOMetadata metadata = writer.getDefaultImageMetadata(new ImageTypeSpecifier(indexed), null);
        IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(IMAGE_METADATA);
        child(root, "ImageDescriptor").setAttribute("interlaceFlag", "FALSE");
        IIOMetadataNode control = child(root, "GraphicControlExtension");
        control.setAttribute("disposalMethod", "restoreToBackgroundColor");
        control.setAttribute("userInputFlag", "FALSE");
        control.setAttribute("transparentColorFlag", transparent ? "TRUE" : "FALSE");
        control.setAttribute("delayTime", Integer.toString(frameDelay(frameIndex, fps)));
        control.setAttribute("transparentColorIndex", "0");
        if (frames == 0) {
            IIOMetadataNode loop = new IIOMetadataNode("ApplicationExtension");
            loop.setAttribute("applicationID", "NETSCAPE");
            loop.setAttribute("authenticationCode", "2.0");
            loop.setUserObject(new byte[]{1, 0, 0});
            child(root, "ApplicationExtensions").appendChild(loop);
        }
        metadata.setFromTree(IMAGE_METADATA, root);
        writer.writeToSequence(new IIOImage(indexed, null, metadata), null);
        frames++;
        ExportMetrics.elapsed(ExportMetrics.Stage.GIF_WRITE, measured);
    }

    /**
     * Quantizer wrapper providing thread-isolated frame quantization workspaces.
     */
    static final class Quantizer {
        private final Palette palette = new Palette();
        private final boolean transparent;
        private final int[] protectedColors;

        /** Constructs a new {@link Quantizer}. */
        Quantizer(boolean transparent, int[] protectedColors) {
            this.transparent = transparent;
            this.protectedColors = protectedColors;
        }

        /** Quantizes a media pixels buffer into an indexed buffered image. */
        BufferedImage quantize(MediaPixels pixels) throws IOException {
            long measured = ExportMetrics.start();
            try {
                return palette.quantize(pixels, transparent, protectedColors, 256);
            } finally {
                ExportMetrics.elapsed(ExportMetrics.Stage.GIF_QUANTIZE, measured);
            }
        }
    }

    /** GIF stores hundredths of a second; accumulated rounding keeps the total exact. */
    static int frameDelay(int index, int fps) {
        return (int) (Math.round((index + 1L) * 100.0 / fps) - Math.round(index * 100.0 / fps));
    }

    /** Normalizes and deduplicates protected color arrays. */
    private static int[] normalizeProtectedColors(int[] supplied) {
        if (supplied == null) {
            throw new IllegalArgumentException("Protected GIF colors must not be null");
        }
        int[] normalized = supplied.clone();
        for (int index = 0; index < normalized.length; index++) {
            normalized[index] &= 0xFFFFFF;
        }
        Arrays.sort(normalized);
        int count = 0;
        for (int color : normalized) {
            if (count != 0 && normalized[count - 1] == color) {
                continue;
            }
            if (count == 192) {
                throw new IllegalArgumentException("GIF exports support at most 192 distinct protected colors");
            }
            normalized[count++] = color;
        }
        return Arrays.copyOf(normalized, count);
    }

    /** Quantize custom-font edges once so subsequent model frames cannot make the UI flicker. */
    static UiFrame prepareStaticUi(BufferedImage image, int[] protectedColors) throws IOException {
        BufferedImage indexed = new Palette().quantize(image, false, normalizeProtectedColors(protectedColors), 192);
        IndexColorModel model = (IndexColorModel) indexed.getColorModel();
        boolean[] used = new boolean[model.getMapSize()];
        for (byte index : ((DataBufferByte) indexed.getRaster().getDataBuffer()).getData()) {
            used[index & 255] = true;
        }
        int[] colors = new int[used.length];
        int count = 0;
        for (int index = 0; index < used.length; index++) {
            if (used[index]) {
                colors[count++] = model.getRGB(index);
            }
        }
        return new UiFrame(indexed, normalizeProtectedColors(Arrays.copyOf(colors, count)));
    }

    /** Represents a pre-quantized static UI frame with its stable color palette. */
    public record UiFrame(BufferedImage image, int[] colors) {}

    /**
     * {@inheritDoc}
     */
    @Override
    public void finish() throws IOException {
        long measured = ExportMetrics.start();
        checkOpen();
        if (frames == 0) {
            throw new IOException("Cannot finish a GIF without frames");
        }
        writer.endWriteSequence();
        output.flush();
        finished = true;
        ExportMetrics.elapsed(ExportMetrics.Stage.FINALIZATION, measured);
    }

    /** Checks whether the GIF encoder is open and active. */
    private void checkOpen() throws IOException {
        if (closed || finished) {
            throw new IOException("GIF encoder is closed");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        writer.dispose();
        try {
            output.close();
        } catch (IOException ignored) { }
    }

    /** Helper to retrieve or create a child IIOMetadataNode. */
    private static IIOMetadataNode child(IIOMetadataNode parent, String name) {
        for (Node item = parent.getFirstChild(); item != null; item = item.getNextSibling()) {
            if (name.equals(item.getNodeName())) {
                return (IIOMetadataNode) item;
            }
        }
        IIOMetadataNode node = new IIOMetadataNode(name);
        parent.appendChild(node);
        return node;
    }

    /**
     * High-performance median-cut color quantizer with dithering and protected color support.
     */
    private static final class Palette {
        private static final int BIN_COUNT = 32 * 32 * 32;
        private final int[] counts = new int[BIN_COUNT], nearest = new int[BIN_COUNT];
        private final long[] red = new long[BIN_COUNT], green = new long[BIN_COUNT], blue = new long[BIN_COUNT];
        private final int[] bins = new int[BIN_COUNT];
        private final long[] occupiedBins = new long[BIN_COUNT / Long.SIZE];
        private final int[] protectedKeys = new int[512], protectedValues = new int[512];
        private int[] lastProtected;
        private int occupied;
        private final Box[] boxes = new Box[512], heap = new Box[512];
        private int allocatedBoxes, heapSize;
        private Box head, tail;
        private int[] scratchPixels, colors, rawRowPixels;
        private float[][] errors;
        private WritableRaster raster;

        /** Quantizes a standard {@link BufferedImage}. */
        BufferedImage quantize(BufferedImage image, boolean transparent, int[] protectedColors, int maximumColors) throws IOException {
            int width = image.getWidth();
            int height = image.getHeight();
            int[] pixels;
            pixels = MediaPixels.packedPixels(image);
            if (pixels == null) {
                if (scratchPixels == null || scratchPixels.length != width * height) {
                    scratchPixels = new int[width * height];
                }
                pixels = image.getRGB(0, 0, width, height, scratchPixels, 0, width);
            }
            return quantize(pixels, null, width, height, transparent, protectedColors, maximumColors);
        }

        /** Quantizes raw {@link MediaPixels}. */
        BufferedImage quantize(MediaPixels pixels, boolean transparent, int[] protectedColors, int maximumColors) throws IOException {
            return quantize(null, pixels, pixels.width, pixels.height, transparent, protectedColors, maximumColors);
        }

        /** Internal core quantization routine. */
        private BufferedImage quantize(int[] pixels, MediaPixels raw, int width, int height,
                                       boolean transparent, int[] protectedColors, int maximumColors) throws IOException {
            long measured = ExportMetrics.start();
            int matte = raw == null ? pixels[0] : raw.argb((height - 1) * width * 4);
            int[] sourcePixels = pixels;
            if (raw != null) {
                if (rawRowPixels == null || rawRowPixels.length != width) {
                    rawRowPixels = new int[width];
                }
                sourcePixels = rawRowPixels;
            }
            boolean reserveMatte = !transparent || (matte >>> 24) >= 128;
            for (int word = 0; word < occupiedBins.length; word++) {
                long remaining = occupiedBins[word];
                occupiedBins[word] = 0;
                while (remaining != 0) {
                    int bin = word * Long.SIZE + Long.numberOfTrailingZeros(remaining);
                    counts[bin] = 0; red[bin] = green[bin] = blue[bin] = 0;
                    remaining &= remaining - 1;
                }
            }
            occupied = 0;
            protectedLookup(protectedColors);
            for (int y = 0; y < height; y++) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("GIF export cancelled");
                }
                int row = y * width;
                if (raw != null) {
                    raw.readRow(y, sourcePixels);
                    row = 0;
                }
                for (int x = 0; x < width; x++) {
                    int pixel = sourcePixels[row + x];
                    if ((transparent && (pixel >>> 24) < 128) || (reserveMatte && pixel == matte)
                            || protectedIndex(pixel) >= 0) {
                        continue;
                    }
                    int bin = bin(pixel >>> 16 & 255, pixel >>> 8 & 255, pixel & 255);
                    if (counts[bin] == 0) {
                        occupied++;
                        occupiedBins[bin / Long.SIZE] |= 1L << (bin % Long.SIZE);
                    }
                    counts[bin]++;
                    red[bin] += pixel >>> 16 & 255;
                    green[bin] += pixel >>> 8 & 255;
                    blue[bin] += pixel & 255;
                }
            }
            int occupiedIndex = 0;
            for (int word = 0; word < occupiedBins.length; word++) {
                long remaining = occupiedBins[word];
                while (remaining != 0) {
                    bins[occupiedIndex++] = word * Long.SIZE + Long.numberOfTrailingZeros(remaining);
                    remaining &= remaining - 1;
                }
            }
            int firstColor = transparent ? 1 : 0;
            int protectedStart = firstColor;
            firstColor += protectedColors.length;
            int protectedMatte = protectedIndex(matte);
            int matteIndex = !reserveMatte ? -1 : protectedMatte >= 0 ? protectedStart + protectedMatte : firstColor++;
            if (firstColor > maximumColors || firstColor == maximumColors && occupied != 0) {
                throw new IllegalArgumentException("Protected GIF colors leave no space to quantize the static UI");
            }
            allocatedBoxes = heapSize = 0;
            head = tail = null;
            int boxCount = 0;
            if (occupied != 0) {
                appendBox(0, occupied);
                boxCount = 1;
            }
            while (boxCount < maximumColors - firstColor && heapSize != 0) {
                Box box = popBox();
                if (box.previous == null) {
                    head = box.next;
                } else {
                    box.previous.next = box.next;
                }
                if (box.next == null) {
                    tail = box.previous;
                } else {
                    box.next.previous = box.previous;
                }
                int cut = box.split(bins, counts);
                appendBox(box.start, cut);
                appendBox(cut, box.end);
                boxCount++;
            }
            if (colors == null || colors.length != maximumColors) {
                colors = new int[maximumColors];
            }
            Arrays.fill(colors, 0);
            for (int index = 0; index < protectedColors.length; index++) {
                colors[protectedStart + index] = 0xFF000000 | protectedColors[index];
            }
            if (reserveMatte) {
                colors[matteIndex] = 0xFF000000 | matte;
            }
            int next = firstColor;
            for (Box box = head; box != null; box = box.next) {
                long r = 0, g = 0, b = 0;
                for (int i = box.start; i < box.end; i++) {
                    int bin = bins[i];
                    r += red[bin];
                    g += green[bin];
                    b += blue[bin];
                }
                colors[next++] = 0xFF000000 | (int) (r / box.weight) << 16
                        | (int) (g / box.weight) << 8 | (int) (b / box.weight);
            }
            if (next == (transparent ? 1 : 0)) {
                colors[next++] = 0xFF000000;
            }
            Arrays.fill(colors, next, colors.length, colors[next - 1]);
            IndexColorModel model = new IndexColorModel(8, maximumColors, colors, 0, transparent, transparent ? 0 : -1,
                    java.awt.image.DataBuffer.TYPE_BYTE);
            if (raster == null || raster.getWidth() != width || raster.getHeight() != height) {
                raster = model.createCompatibleWritableRaster(width, height);
            }
            BufferedImage result = new BufferedImage(model, raster, false, null);
            byte[] indices = ((DataBufferByte) result.getRaster().getDataBuffer()).getData();
            if (transparent) {
                Arrays.fill(indices, (byte) 0);
            }
            Arrays.fill(nearest, -1);
            if (errors == null || errors[0].length != width + 2) {
                errors = new float[6][width + 2];
            }
            for (float[] row : errors) {
                Arrays.fill(row, 0f);
            }
            ExportMetrics.elapsed(ExportMetrics.Stage.GIF_PALETTE, measured);
            measured = ExportMetrics.start();
            for (int y = 0; y < height; y++) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("GIF export cancelled");
                }
                boolean reverse = (y & 1) != 0;
                int step = reverse ? -1 : 1;
                int sourceRow = y * width;
                if (raw != null) {
                    raw.readRow(y, sourcePixels);
                    sourceRow = 0;
                }
                for (int n = 0; n < width; n++) {
                    int x = reverse ? width - 1 - n : n;
                    int offset = y * width + x;
                    int pixel = sourcePixels[sourceRow + x];
                    if (transparent && (pixel >>> 24) < 128) {
                        continue;
                    }
                    int protectedIndex = protectedIndex(pixel);
                    if (protectedIndex >= 0) {
                        indices[offset] = (byte) (protectedStart + protectedIndex);
                        continue;
                    }
                    if (reserveMatte && pixel == matte) {
                        indices[offset] = (byte) matteIndex;
                        continue;
                    }
                    int r = Math.clamp(Math.round((pixel >>> 16 & 255) + errors[0][x + 1]), 0, 255);
                    int g = Math.clamp(Math.round((pixel >>> 8 & 255) + errors[1][x + 1]), 0, 255);
                    int b = Math.clamp(Math.round((pixel & 255) + errors[2][x + 1]), 0, 255);
                    int key = bin(r, g, b);
                    int colorIndex = nearest[key];
                    if (colorIndex < 0) {
                        nearest[key] = colorIndex = nearest(colors, next, transparent ? 1 : 0, r, g, b);
                    }
                    indices[offset] = (byte) colorIndex;
                    int color = colors[colorIndex];
                    spread(errors[0], errors[3], x + 1, step, r - (color >>> 16 & 255));
                    spread(errors[1], errors[4], x + 1, step, g - (color >>> 8 & 255));
                    spread(errors[2], errors[5], x + 1, step, b - (color & 255));
                }
                for (int channel = 0; channel < 3; channel++) {
                    float[] used = errors[channel];
                    errors[channel] = errors[channel + 3];
                    errors[channel + 3] = used;
                    Arrays.fill(used, 0f);
                }
            }
            ExportMetrics.elapsed(ExportMetrics.Stage.GIF_DITHER, measured);
            return result;
        }

        private static int nearest(int[] colors, int count, int start, int r, int g, int b) {
            int best = start;
            int distance = Integer.MAX_VALUE;
            for (int i = start; i < count; i++) {
                int dr = r - (colors[i] >>> 16 & 255), dg = g - (colors[i] >>> 8 & 255), db = b - (colors[i] & 255);
                int value = dr * dr * 2 + dg * dg * 3 + db * db;
                if (value < distance) {
                    best = i;
                    distance = value;
                }
            }
            return best;
        }

        private static void spread(float[] row, float[] below, int x, int step, float error) {
            row[x + step] += error * (7f / 16f);
            below[x - step] += error * (3f / 16f);
            below[x] += error * (5f / 16f);
            below[x + step] += error * (1f / 16f);
        }

        private static int bin(int red, int green, int blue) {
            return (red >> 3) << 10 | (green >> 3) << 5 | blue >> 3;
        }

        private void protectedLookup(int[] colors) {
            if (Arrays.equals(lastProtected, colors)) {
                return;
            }
            lastProtected = colors.clone();
            Arrays.fill(protectedKeys, 0);
            for (int i = 0; i < colors.length; i++) {
                int key = (colors[i] & 0xFFFFFF) | 0x1000000;
                int slot = hash(key);
                while (protectedKeys[slot] != 0) {
                    slot = (slot + 1) & 511;
                }
                protectedKeys[slot] = key;
                protectedValues[slot] = i;
            }
        }

        private int protectedIndex(int color) {
            int rgb = color & 0xFFFFFF;
            int length = lastProtected.length;
            if (length <= 4) {
                if (length != 0 && lastProtected[0] == rgb) {
                    return 0;
                }
                if (length > 1 && lastProtected[1] == rgb) {
                    return 1;
                }
                if (length > 2 && lastProtected[2] == rgb) {
                    return 2;
                }
                if (length > 3 && lastProtected[3] == rgb) {
                    return 3;
                }
                return -1;
            }
            int key = rgb | 0x1000000;
            int slot = hash(key);
            while (protectedKeys[slot] != 0) {
                if (protectedKeys[slot] == key) {
                    return protectedValues[slot];
                }
                slot = (slot + 1) & 511;
            }
            return -1;
        }

        private static int hash(int key) {
            return (key * 0x9E3779B9) >>> 23;
        }

        private void appendBox(int start, int end) {
            int order = allocatedBoxes++;
            Box box = boxes[order];
            if (box == null) {
                boxes[order] = box = new Box();
            }
            box.initialize(bins, counts, start, end, order);
            box.previous = tail;
            box.next = null;
            if (tail == null) {
                head = box;
            } else {
                tail.next = box;
            }
            tail = box;
            if (end - start < 2) {
                return;
            }
            int index = heapSize++;
            while (index != 0) {
                int parent = (index - 1) >>> 1;
                if (!before(box, heap[parent])) {
                    break;
                }
                heap[index] = heap[parent];
                index = parent;
            }
            heap[index] = box;
        }

        private Box popBox() {
            Box result = heap[0], last = heap[--heapSize];
            heap[heapSize] = null;
            if (heapSize != 0) {
                int index = 0;
                while (index * 2 + 1 < heapSize) {
                    int child = index * 2 + 1;
                    if (child + 1 < heapSize && before(heap[child + 1], heap[child])) {
                        child++;
                    }
                    if (!before(heap[child], last)) {
                        break;
                    }
                    heap[index] = heap[child];
                    index = child;
                }
                heap[index] = last;
            }
            return result;
        }

        private static boolean before(Box a, Box b) {
            return a.score > b.score || a.score == b.score && a.order < b.order;
        }

        /**
         * Represents a color box in median-cut quantization.
         */
        private static final class Box {
            int start, end, weight, channel, order;
            long score;
            Box previous, next;

            void initialize(int[] bins, int[] counts, int start, int end, int order) {
                this.start = start;
                this.end = end;
                this.order = order;
                int minR = 31, minG = 31, minB = 31, maxR = 0, maxG = 0, maxB = 0;
                int total = 0;
                for (int i = start; i < end; i++) {
                    int bin = bins[i];
                    total += counts[bin];
                    int r = bin >> 10, g = bin >> 5 & 31, b = bin & 31;
                    minR = Math.min(minR, r);
                    maxR = Math.max(maxR, r);
                    minG = Math.min(minG, g);
                    maxG = Math.max(maxG, g);
                    minB = Math.min(minB, b);
                    maxB = Math.max(maxB, b);
                }
                weight = total;
                channel = 0;
                int range = maxR - minR;
                if (maxG - minG > range) {
                    channel = 1;
                    range = maxG - minG;
                }
                if (maxB - minB > range) {
                    channel = 2;
                    range = maxB - minB;
                }
                score = (long) range * weight;
            }

            int split(int[] bins, int[] counts) {
                for (int i = start; i < end; i++) {
                    bins[i] |= component(bins[i], channel) << 15;
                }
                Arrays.sort(bins, start, end);
                for (int i = start; i < end; i++) {
                    bins[i] &= BIN_COUNT - 1;
                }
                int cut = start, total = 0;
                do {
                    total += counts[bins[cut++]];
                } while (cut < end - 1 && total < weight / 2);
                return cut;
            }

            private static int component(int bin, int channel) {
                return bin >> ((2 - channel) * 5) & 31;
            }
        }
    }
}
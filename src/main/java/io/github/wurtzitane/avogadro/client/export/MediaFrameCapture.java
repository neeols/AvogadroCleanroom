package io.github.wurtzitane.avogadro.client.export;

import io.github.wurtzitane.avogadro.client.PixelFont;

import io.github.wurtzitane.avogadro.client.*;
import io.github.wurtzitane.avogadro.api.client.MoleculeRenderOptions;
import io.github.wurtzitane.avogadro.molecule.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.text.TextFormatting;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * Incremental offscreen capture controller for media exports.
 * <p>Only complete, owned frames cross into encoder workers.
 */
final class MediaFrameCapture implements AutoCloseable {

    /** Size limit for buffer slices in bytes (1 MB). */
    private static final int SLICE_BYTES = 1024 * 1024;

    /** Background thread pool executor for media preparation and static UI encoding. */
    private static final ThreadPoolExecutor PREPARATION = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2), action -> {
        var thread = new Thread(action, "Avogadro media preparation");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    static {
        PREPARATION.allowCoreThreadTimeOut(true);
    }

    /** Export options configuration. */
    private final ExportOptions options;

    /** Optional molecule render options. */
    private final MoleculeRenderOptions rendering;

    /** List of gallery layout cells. */
    private final List<MediaLayout.Cell> cells;

    /** Element palette snapshot. */
    private final ElementPalette.Snapshot palette;

    /** Optional item export options. */
    private final ItemExportOptions item;

    /** Optional atom export options. */
    private final AtomExportOptions atom;

    /** Atomic inspection renderer for 3D atom exports. */
    private final AtomicInspectionRenderer atomRenderer;

    /** Molecule camera for 3D atom viewports. */
    private final MoleculeCamera atomCamera;

    /** Graphics limits snapshot. */
    private final MediaGraphicsLimits.Limits limits;

    /** Pixel memory pool for zero-allocation reuse. */
    private final MediaPixelPool pixelPool;

    /** Total bytes, row bytes, and slice row counts for frame capture. */
    private final int totalBytes, rowBytes, sliceRows;

    /** OpenGL object IDs for framebuffers, textures, and buffers. */
    private int framebuffer, colorTexture, depthBuffer, staticTexture;

    /** Direct byte buffer for pixel transfers. */
    private ByteBuffer pixels;

    /** Array of protected RGB colors. */
    private int[] protectedColors = new int[0];

    /** Internal state trackers for cell indices, preparation phases, and rows. */
    private int nextCell, phase, prepIndex, uploadRow;

    /** Flags tracking frame readiness, reading state, and closure. */
    private boolean frameOpen, frameReady, syncReading, closed;

    /** Tooltip card artwork image reference. */
    private TooltipCardArtwork.Image artwork;

    /** Prepared static UI image data record. */
    private StaticUi upload;

    /** Asynchronous future task for preparation. */
    private Future<StaticUi> preparation;

    /** Media pixel references for static and processing phases. */
    private MediaPixels staticPixels, copyLease;

    /** Copy offset tracker. */
    private int copyOffset;

    /** Volatile reference to processing pixels. */
    private volatile MediaPixels processingPixels;

    /** Atomic boolean flag claiming processing pixels. */
    private java.util.concurrent.atomic.AtomicBoolean processingClaim;

    /** Flag indicating read progress. */
    private boolean readProgress;

    /** Molecule and atom render cursors. */
    private MoleculeRenderer.ExportCursor modelCursor;
    private AtomicInspectionRenderer.ExportCursor atomCursor;

    /** Queue of recycled buffered images. */
    private final ArrayBlockingQueue<BufferedImage> recycled = new ArrayBlockingQueue<>(4);

    /** Pixel pack buffer objects for asynchronous readback. */
    private final int[] readBuffers = new int[2];

    /** Sync fences and fence timestamp arrays for GPU synchronization. */
    private final long[] fences = new long[2], fenceTimes = new long[2];

    /** Read head index, pending read count, and submitted frames count. */
    private int readHead, pendingReads, submittedFrames;

    /** Flag indicating whether asynchronous GPU readback is supported. */
    private boolean asynchronous;

    /** Record representing pre-rendered static UI bytes and colors. */
    private record StaticUi(byte[] rgba, int[] colors) {}

    /**
     * Constructs a new {@link MediaFrameCapture} for standard molecule definitions.
     *
     * @param options     export configuration options
     * @param definitions list of molecule definitions
     * @throws IOException if framebuffer support is missing or limits are exceeded
     */
    MediaFrameCapture(ExportOptions options, List<MoleculeDef> definitions) throws IOException {
        this(options, definitions, null, null, ElementPalette.snapshot());
    }

    /**
     * Constructs a new {@link MediaFrameCapture} supporting item cards and artwork.
     *
     * @param options     export configuration options
     * @param definitions list of molecule definitions
     * @param item        optional item export options
     * @param artwork     optional card artwork image
     * @param palette     element palette snapshot
     * @throws IOException if framebuffer support is missing or limits are exceeded
     */
    MediaFrameCapture(ExportOptions options, List<MoleculeDef> definitions, ItemExportOptions item,
                      TooltipCardArtwork.Image artwork, ElementPalette.Snapshot palette) throws IOException {
        this(options, definitions, item, artwork, palette, null);
    }

    /**
     * Constructs a new {@link MediaFrameCapture} supporting atom exports.
     *
     * @param options     export configuration options
     * @param definitions list of molecule definitions
     * @param item        optional item export options
     * @param artwork     optional card artwork image
     * @param palette     element palette snapshot
     * @param atom        optional atom export options
     * @throws IOException if framebuffer support is missing or limits are exceeded
     */
    MediaFrameCapture(ExportOptions options, List<MoleculeDef> definitions, ItemExportOptions item,
                      TooltipCardArtwork.Image artwork, ElementPalette.Snapshot palette, AtomExportOptions atom) throws IOException {
        this(options, definitions, item, artwork, palette, atom, null);
    }

    /**
     * Constructs a fully specified {@link MediaFrameCapture} instance.
     *
     * @param options     export configuration options
     * @param definitions list of molecule definitions
     * @param item        optional item export options
     * @param artwork     optional card artwork image
     * @param palette     element palette snapshot
     * @param atom        optional atom export options
     * @param rendering   optional molecule render options
     * @throws IOException if framebuffer support is missing or limits are exceeded
     */
    MediaFrameCapture(ExportOptions options, List<MoleculeDef> definitions, ItemExportOptions item,
                      TooltipCardArtwork.Image artwork, ElementPalette.Snapshot palette, AtomExportOptions atom,
                      MoleculeRenderOptions rendering) throws IOException {
        this.options = options;
        this.item = item;
        this.artwork = artwork;
        this.palette = palette;
        this.atom = atom;
        this.rendering = rendering;
        atomRenderer = atom == null ? null : new AtomicInspectionRenderer(atom.definition(), palette);
        atomCamera = atom == null ? null : new MoleculeCamera(atom.details() ? options.width() * 3 / 5 : options.width(), options.height());
        pixelPool = new MediaPixelPool(options.width(), options.height(), options.background() == null);
        totalBytes = Math.multiplyExact(Math.multiplyExact(options.width(), options.height()), 4);
        rowBytes = options.width() * 4;
        sliceRows = Math.max(1, SLICE_BYTES / rowBytes);
        limits = MediaGraphicsLimits.current();
        if (!OpenGlHelper.framebufferSupported) {
            throw new IOException("Media export requires framebuffer support.");
        }
        if (Math.max(options.width(), options.height()) > limits.textureSize()) {
            throw new IOException("Export dimensions exceed this graphics device's texture limit.");
        }
        asynchronous = !Boolean.getBoolean("avogadro.export.forceSynchronous") && MediaGraphicsLimits.asynchronous(GL.getCapabilities());
        FontRenderer font = PixelFont.renderer();
        cells = item == null && atom == null ? MediaLayout.create(options, definitions, MediaFrameCapture::name, font::getStringWidth, font.FONT_HEIGHT) : List.of();
    }

    /**
     * Advances the capture sequence and returns a rendered buffered image for the specified frame.
     *
     * @param frame the zero-based frame index
     * @return the rendered buffered image, or {@code null} if not yet ready
     */
    BufferedImage advance(int frame) {
        MediaPixels raw = advancePixels(frame);
        if (raw == null) {
            return null;
        }
        try {
            return raw.image(recycled.poll());
        } finally {
            recycle(raw);
        }
    }

    /**
     * Advances the capture sequence and returns raw media pixels.
     * At most one complete frame is returned; all partial preparation/draw/read state remains private.
     *
     * @param frame the zero-based frame index
     * @return the completed {@link MediaPixels}, or {@code null} if still processing
     */
    MediaPixels advancePixels(int frame) {
        if (closed) {
            throw new IllegalStateException("Capture closed");
        }
        try (GraphicsScope ignored = new GraphicsScope()) {
            for (int work = 0; work < 4096 && FrameWorkBudget.canExport(); work++) {
                if (phase < 10) {
                    boolean readStage = phase == 7;
                    if (!readStage && !FrameWorkBudget.tryExport()) {
                        return null;
                    }
                    long started = System.nanoTime();
                    boolean progressed;
                    try {
                        progressed = prepareStep();
                    } finally {
                        long elapsed = System.nanoTime() - started;
                        if (!readStage) {
                            FrameWorkBudget.recordExport(elapsed);
                        }
                        ExportMetrics.record(ExportMetrics.Stage.PREPARATION, elapsed);
                    }
                    if (!progressed) {
                        return null;
                    }
                    continue;
                }
                MediaPixels complete = asynchronous ? pollReadback() : syncReading ? readSynchronous() : null;
                if (complete != null) {
                    return complete;
                }
                if (syncReading) {
                    return null;
                }
                if (asynchronous && (submittedFrames == options.totalFrames() || pendingReads == readBuffers.length)) {
                    if (readProgress) {
                        continue;
                    }
                    return null;
                }
                int captureFrame = asynchronous ? submittedFrames : frame;
                if (captureFrame >= options.totalFrames()) {
                    return null;
                }
                prepareTarget();
                if (frameReady) {
                    if (!FrameWorkBudget.tryExport()) {
                        return null;
                    }
                    long started = System.nanoTime();
                    if (asynchronous) {
                        issueReadback();
                        submittedFrames++;
                    } else {
                        syncReading = true;
                        copyOffset = 0;
                    }
                    frameReady = false;
                    FrameWorkBudget.recordExport(System.nanoTime() - started);
                    continue;
                }
                if (!frameOpen) {
                    if (!FrameWorkBudget.tryExport()) {
                        return null;
                    }
                    long started = System.nanoTime();
                    if (staticTexture != 0) {
                        drawStaticCard();
                    } else {
                        clearTarget();
                    }
                    frameOpen = true;
                    nextCell = 0;
                    FrameWorkBudget.recordExport(System.nanoTime() - started);
                    continue;
                }
                boolean finished;
                long drawStarted = ExportMetrics.start();
                int timer = ExportMetrics.gpuBegin();
                try {
                    if (atom != null) {
                        if (atomCursor == null) {
                            atomCursor = atomRenderer.beginExport(atom.details() ? options.width() * 3 / 5 : options.width(), options.height(), atomCamera, atom.atom(),
                                    (long) (options.frameTimeSeconds(captureFrame) * 1_000_000_000L), options.yawAtFrame(captureFrame), options.durationSeconds());
                        }
                        finished = atomCursor.advance();
                        if (finished) {
                            atomCursor.close();
                            atomCursor = null;
                        }
                    } else {
                        if (modelCursor == null) {
                            if (item != null) {
                                ItemCardLayout layout = item.layout();
                                int scale = item.scale();
                                modelCursor = model(item.selection().definition().structure(), layout.modelLeft() * scale, layout.modelTop() * scale,
                                        layout.modelWidth() * scale, layout.modelHeight() * scale, layout.imageSize() * scale, captureFrame);
                            } else {
                                MediaLayout.Cell cell = cells.get(nextCell);
                                modelCursor = model(cell.definition().structure(), cell.modelLeft(), cell.modelTop(), cell.modelWidth(), cell.modelHeight(), cell.imageSize(), captureFrame);
                            }
                            if (modelCursor == null) {
                                return null;
                            }
                        }
                        boolean cellDone = modelCursor.advance();
                        if (cellDone) {
                            modelCursor.close();
                            modelCursor = null;
                            nextCell++;
                        }
                        finished = cellDone && (item != null || nextCell == cells.size());
                    }
                } finally {
                    ExportMetrics.gpuEnd(timer);
                    ExportMetrics.elapsed(ExportMetrics.Stage.DRAW_SUBMISSION, drawStarted);
                }
                if (!finished) {
                    continue;
                }
                frameOpen = false;
                frameReady = true;
            }
            return null;
        }
    }

    /** Creates a model export cursor for rendering a molecule structure. */
    private MoleculeRenderer.ExportCursor model(MoleculeStructure structure, int x, int y, int width, int height, int size, int frame) {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        return rendering == null ? MoleculeRenderer.beginExport(structure, x, y, width, height, size, options.yawAtFrame(frame), options.cubic(), palette)
                : MoleculeRenderer.beginExport(structure, x, y, width, height, size, rendering.yawAt(options.frameTimeSeconds(frame)), rendering);
    }

    /** Each stage contains one allocation, label/card, bounded transfer, or worker handoff. */
    private boolean prepareStep() {
        prepareTargetIfAllocated();
        switch (phase) {
            case 0 -> {
                framebuffer = OpenGlHelper.glGenFramebuffers();
                colorTexture = GL11.glGenTextures();
                depthBuffer = OpenGlHelper.glGenRenderbuffers();
                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
                // FontRenderer must see this binding change before it binds its glyph atlas.
                GlStateManager.bindTexture(colorTexture);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, options.width(), options.height(), 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
                OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, framebuffer);
                OpenGlHelper.glFramebufferTexture2D(OpenGlHelper.GL_FRAMEBUFFER, OpenGlHelper.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, colorTexture, 0);
                OpenGlHelper.glBindRenderbuffer(OpenGlHelper.GL_RENDERBUFFER, depthBuffer);
                OpenGlHelper.glRenderbufferStorage(OpenGlHelper.GL_RENDERBUFFER, GL14.GL_DEPTH_COMPONENT24, options.width(), options.height());
                OpenGlHelper.glFramebufferRenderbuffer(OpenGlHelper.GL_FRAMEBUFFER, OpenGlHelper.GL_DEPTH_ATTACHMENT, OpenGlHelper.GL_RENDERBUFFER, depthBuffer);
                if (OpenGlHelper.glCheckFramebufferStatus(OpenGlHelper.GL_FRAMEBUFFER) != OpenGlHelper.GL_FRAMEBUFFER_COMPLETE) {
                    throw new IllegalStateException("Could not allocate export framebuffer");
                }
                pixels = MemoryUtil.memAlloc(Math.min(totalBytes, sliceRows * rowBytes));
                phase = 1;
            }
            case 1, 2 -> {
                if (asynchronous) {
                    int slot = phase - 1;
                    readBuffers[slot] = GL15.glGenBuffers();
                    GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, readBuffers[slot]);
                    GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, totalBytes, GL15.GL_STREAM_READ);
                    if (GL15.glGetBufferParameteri(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_BUFFER_SIZE) != totalBytes) {
                        asynchronous = false;
                        for (int i = 0; i < 2; i++) {
                            if (readBuffers[i] != 0) {
                                GL15.glDeleteBuffers(readBuffers[i]);
                            }
                            readBuffers[i] = 0;
                        }
                    }
                }
                phase++;
            }
            case 3 -> {
                if (item != null) {
                    if (preparation == null) {
                        TooltipCardArtwork.Image image = artwork;
                        if (image == null) {
                            return false;
                        }
                        preparation = submit(() -> new StaticUi(rgba(image.pixels(), options.width(), options.height()), image.protectedColors()));
                        if (preparation == null) {
                            return false;
                        }
                    }
                    if (!preparation.isDone()) {
                        return false;
                    }
                    upload = completed(preparation);
                    preparation = null;
                    artwork = null;
                    newStaticTexture();
                    phase = 4;
                } else {
                    clearTarget();
                    phase = 5;
                }
            }
            case 4 -> {
                if (uploadSlice()) {
                    upload = null;
                    uploadRow = 0;
                    drawStaticCard();
                    phase = 5;
                }
            }
            case 5 -> {
                if (item != null) {
                    if (prepIndex < item.layout().labels().size()) {
                        drawItemLabel(item.layout().labels().get(prepIndex++));
                    } else {
                        phase = 6;
                    }
                } else if (atom != null) {
                    if (atom.details()) {
                        drawAtomCard();
                    }
                    phase = atom.details() ? 6 : 10;
                } else {
                    if (prepIndex < cells.size()) {
                        labels(cells.get(prepIndex++));
                    } else {
                        copyStatic();
                        phase = 10;
                    }
                }
            }
            case 6 -> {
                if (options.format() != ExportOptions.Format.GIF) {
                    copyStatic();
                    phase = 10;
                } else {
                    if (asynchronous) {
                        issueReadback();
                    } else {
                        syncReading = true;
                        copyOffset = 0;
                    }
                    phase = 7;
                }
            }
            case 7 -> {
                MediaPixels raw = asynchronous ? pollReadback() : readSynchronous();
                if (raw == null) {
                    return readProgress;
                }
                staticPixels = raw;
                phase = 8;
            }
            case 8 -> {
                if (preparation == null) {
                    MediaPixels raw = staticPixels;
                    int[] colors = item != null ? protectedArtworkColors() : new int[]{options.background() | 0xFF000000, 0xFF243B3A, 0xFF62716D, ElementCard.PAPER, 0xFF24618A, 0xFFE1E3DA,
                            ElementCard.color(atom.definition().structure().atoms().get(atom.atom()).element().symbol())};
                    processingPixels = raw;
                    processingClaim = new java.util.concurrent.atomic.AtomicBoolean();
                    preparation = submit(() -> {
                        if (!processingClaim.compareAndSet(false, true)) {
                            throw new CancellationException();
                        }
                        try {
                            long started = ExportMetrics.start();
                            BufferedImage image = raw.image(null);
                            ExportMetrics.elapsed(ExportMetrics.Stage.PIXEL_CONVERSION, started);
                            GifMediaEncoder.UiFrame ui = GifMediaEncoder.prepareStaticUi(image, colors);
                            return new StaticUi(rgba(ui.image().getRGB(0, 0, options.width(), options.height(), null, 0, options.width()), options.width(), options.height()), ui.colors());
                        } finally {
                            pixelPool.release(raw);
                        }
                    });
                    if (preparation == null) {
                        processingPixels = null;
                        return false;
                    }
                    staticPixels = null;
                }
                if (!preparation.isDone()) {
                    return false;
                }
                upload = completed(preparation);
                preparation = null;
                protectedColors = upload.colors();
                if (staticTexture == 0) {
                    newStaticTexture();
                }
                phase = 9;
            }
            case 9 -> {
                if (uploadSlice()) {
                    upload = null;
                    uploadRow = 0;
                    phase = 10;
                }
            }
            default -> throw new IllegalStateException("Unknown preparation stage");
        }
        return true;
    }

    /** Retrieves the protected artwork colors. */
    private int[] protectedArtworkColors() {
        return protectedColors;
    }

    /** Prepares target if framebuffer is allocated. */
    private void prepareTargetIfAllocated() {
        if (framebuffer != 0) {
            prepareTarget();
        }
    }

    /** Clears the target framebuffer with background or transparent color. */
    private void clearTarget() {
        int background = options.background() == null ? 0 : options.background();
        GL11.glClearColor((background >> 16 & 255) / 255f, (background >> 8 & 255) / 255f, (background & 255) / 255f, options.background() == null ? 0 : 1);
        GL11.glClearDepth(1);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
    }

    /** Generates a new static texture object. */
    private void newStaticTexture() {
        staticTexture = GL11.glGenTextures();
        GlStateManager.bindTexture(staticTexture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, options.width(), options.height(), 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
    }

    /** Uploads a pixel slice buffer to the static texture. */
    private boolean uploadSlice() {
        int rows = Math.min(sliceRows, options.height() - uploadRow), length = rows * rowBytes;
        pixels.clear();
        pixels.put(upload.rgba(), uploadRow * rowBytes, length).flip();
        GlStateManager.bindTexture(staticTexture);
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SWAP_BYTES, GL11.GL_FALSE);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, uploadRow, options.width(), rows, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        uploadRow += rows;
        protectedColors = upload.colors();
        return uploadRow == options.height();
    }

    /** Copies the current framebuffer content into the static texture. */
    private void copyStatic() {
        if (staticTexture == 0) {
            newStaticTexture();
        } else {
            GlStateManager.bindTexture(staticTexture);
        }
        GL11.glReadBuffer(OpenGlHelper.GL_COLOR_ATTACHMENT0);
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, options.width(), options.height());
    }

    /** Draws the atom inspection card layout. */
    private void drawAtomCard() {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GlStateManager.bindTexture(0);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glAlphaFunc(GL11.GL_GREATER, .1f);
        GlStateManager.enableAlpha();
        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate(options.width() * 3 / 5, 0, 0);
            ElementCard.draw(atom.definition(), atom.atom(), options.width() - options.width() * 3 / 5, options.height(), (float) options.textScale());
        } finally {
            GlStateManager.popMatrix();
        }
    }

    /** Draws an individual item card text label. */
    private void drawItemLabel(ItemCardLayout.Label label) {
        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.enableTexture2D();
        GlStateManager.enableAlpha();
        FontRenderer font = PixelFont.wrap(item.selection().font());
        for (var glyph : label.line().glyphs()) {
            float scale = item.scale() * label.textScale();
            GL11.glPushMatrix();
            try {
                GL11.glTranslatef((label.left() + glyph.left() * label.textScale()) * item.scale(), (label.top() + glyph.top() * label.textScale()) * item.scale(), 0);
                GL11.glScalef(scale * glyph.scale(), scale * glyph.scale(), 1);
                font.drawStringWithShadow(glyph.text(), 0, 0, 0xFFFFFFFF);
            } finally {
                GL11.glPopMatrix();
            }
        }
    }

    /** Converts an array of ARGB integer colors into an RGBA byte array. */
    private static byte[] rgba(int[] colors, int width, int height) {
        byte[] result = new byte[Math.multiplyExact(colors.length, 4)];
        int out = 0;
        for (int y = height - 1; y >= 0; y--) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException();
            }
            for (int x = 0; x < width; x++) {
                int color = colors[y * width + x];
                result[out++] = (byte) (color >> 16);
                result[out++] = (byte) (color >> 8);
                result[out++] = (byte) color;
                result[out++] = (byte) 255;
            }
        }
        return result;
    }

    /** Submits a preparation callable task to the background executor. */
    private static Future<StaticUi> submit(Callable<StaticUi> action) {
        PREPARATION.purge();
        try {
            return PREPARATION.submit(action);
        } catch (RejectedExecutionException busy) {
            return null;
        }
    }

    /** Retrieves the completed result from a preparation future task. */
    private static StaticUi completed(Future<StaticUi> task) {
        try {
            return task.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException();
        } catch (ExecutionException failure) {
            throw new IllegalStateException("Cannot prepare export artwork", failure.getCause());
        }
    }

    /** Retrieves protected colors array. */
    int[] protectedColors() {
        return protectedColors;
    }

    /** Recycles a buffered image back into the queue. */
    void recycle(BufferedImage image) {
        recycled.offer(image);
    }

    /** Recycles media pixels back into the pool. */
    void recycle(MediaPixels image) {
        pixelPool.release(image);
    }

    /** Discards queued frames by releasing their media pixels. */
    <T> void discardFrames(Queue<T> frames, Function<T, MediaPixels> values) {
        pixelPool.releaseQueued(frames, values);
    }

    /** Issues an asynchronous GPU readback command using a PBO ring buffer. */
    private void issueReadback() {
        int tail = (readHead + pendingReads) % 2;
        if (pendingReads == 2) {
            throw new IllegalStateException("Export PBO ring full");
        }
        prepareReadback();
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, readBuffers[tail]);
        GL11.glReadPixels(0, 0, options.width(), options.height(), GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
        fences[tail] = fence();
        fenceTimes[tail] = System.nanoTime();
        if (fences[tail] == 0) {
            throw new IllegalStateException("Could not fence export readback");
        }
        GL11.glFlush();
        pendingReads++;
    }

    /** Polls and maps completed asynchronous GPU readback buffers. */
    private MediaPixels pollReadback() {
        readProgress = false;
        if (pendingReads == 0) {
            return null;
        }
        if (copyLease == null) {
            int state = waitFence(fences[readHead]);
            if (state == GL32.GL_TIMEOUT_EXPIRED) {
                return null;
            }
            if (state == GL32.GL_WAIT_FAILED) {
                throw new IllegalStateException("Export framebuffer readback failed");
            }
        }
        if (!FrameWorkBudget.tryExport()) {
            return null;
        }
        long started = System.nanoTime();
        if (copyLease == null) {
            copyLease = pixelPool.acquire();
            if (copyLease == null) {
                return null;
            }
            copyOffset = 0;
            ExportMetrics.record(ExportMetrics.Stage.FENCE_LATENCY, System.nanoTime() - fenceTimes[readHead]);
        }
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, readBuffers[readHead]);
        int length = Math.min(sliceRows * rowBytes, totalBytes - copyOffset);
        ByteBuffer mapped = GL.getCapabilities().OpenGL30 ? GL30.glMapBufferRange(GL21.GL_PIXEL_PACK_BUFFER, copyOffset, length, GL30.GL_MAP_READ_BIT)
                : ARBMapBufferRange.glMapBufferRange(GL21.GL_PIXEL_PACK_BUFFER, copyOffset, length, GL30.GL_MAP_READ_BIT);
        if (mapped == null) {
            throw new IllegalStateException("Could not map export pixels");
        }
        boolean valid;
        try {
            mapped.get(0, copyLease.rgba, copyOffset, length);
        } finally {
            valid = GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
        }
        if (!valid) {
            throw new IllegalStateException("Graphics driver discarded export readback buffer");
        }
        copyOffset += length;
        readProgress = true;
        long elapsed = System.nanoTime() - started;
        FrameWorkBudget.recordExport(elapsed);
        ExportMetrics.record(ExportMetrics.Stage.MAP_COPY, elapsed);
        if (copyOffset != totalBytes) {
            return null;
        }
        MediaPixels complete = copyLease;
        copyLease = null;
        copyOffset = 0;
        deleteFence(fences[readHead]);
        fences[readHead] = 0;
        readHead = (readHead + 1) % 2;
        pendingReads--;
        return complete;
    }

    /** Performs a synchronous GPU pixel readback fallback. */
    private MediaPixels readSynchronous() {
        readProgress = false;
        if (!FrameWorkBudget.tryExport()) {
            return null;
        }
        if (copyLease == null) {
            copyLease = pixelPool.acquire();
            if (copyLease == null) {
                return null;
            }
            copyOffset = 0;
        }
        long started = System.nanoTime();
        int rows = Math.min(sliceRows, options.height() - copyOffset / rowBytes), length = rows * rowBytes;
        prepareReadback();
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        pixels.clear();
        pixels.limit(length);
        GL11.glReadPixels(0, copyOffset / rowBytes, options.width(), rows, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        pixels.get(0, copyLease.rgba, copyOffset, length);
        copyOffset += length;
        readProgress = true;
        long elapsed = System.nanoTime() - started;
        FrameWorkBudget.recordExport(elapsed);
        ExportMetrics.record(ExportMetrics.Stage.MAP_COPY, elapsed);
        if (copyOffset != totalBytes) {
            return null;
        }
        MediaPixels complete = copyLease;
        copyLease = null;
        copyOffset = 0;
        syncReading = false;
        return complete;
    }

    /** Configures pixel packing states for frame readback. */
    private void prepareReadback() {
        OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, framebuffer);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
        GL11.glReadBuffer(OpenGlHelper.GL_COLOR_ATTACHMENT0);
    }

    /** Prepares the OpenGL target states for offscreen rendering. */
    private void prepareTarget() {
        OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, framebuffer);
        GL11.glViewport(0, 0, options.width(), options.height());
        GL20.glUseProgram(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        GL11.glDisable(GL11.GL_FOG);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_COLOR_LOGIC_OP);
        GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
        GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
        for (int plane = 0; plane < 6; plane++) {
            GL11.glDisable(GL11.GL_CLIP_PLANE0 + plane);
        }
        for (int unit = 0; unit < limits.textureUnits(); unit++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_TEXTURE_GEN_S);
            GL11.glDisable(GL11.GL_TEXTURE_GEN_T);
            GL11.glDisable(GL11.GL_TEXTURE_GEN_R);
            GL11.glDisable(GL11.GL_TEXTURE_GEN_Q);
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
        GL11.glMatrixMode(GL11.GL_TEXTURE);
        GL11.glLoadIdentity();
        GL11.glDepthRange(0, 1);
        GL11.glColorMask(true, true, true, true);
        GL11.glDepthMask(true);
        GL11.glPixelTransferi(GL11.GL_MAP_COLOR, GL11.GL_FALSE);
        GL11.glPixelTransferf(GL11.GL_RED_SCALE, 1);
        GL11.glPixelTransferf(GL11.GL_RED_BIAS, 0);
        GL11.glPixelTransferf(GL11.GL_GREEN_SCALE, 1);
        GL11.glPixelTransferf(GL11.GL_GREEN_BIAS, 0);
        GL11.glPixelTransferf(GL11.GL_BLUE_SCALE, 1);
        GL11.glPixelTransferf(GL11.GL_BLUE_BIAS, 0);
        GL11.glPixelTransferf(GL11.GL_ALPHA_SCALE, 1);
        GL11.glPixelTransferf(GL11.GL_ALPHA_BIAS, 0);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        GL11.glOrtho(0, options.width(), options.height(), 0, -2000, 2000);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glLoadIdentity();
    }

    /** Draws the pre-rendered static background card texture. */
    private void drawStaticCard() {
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GlStateManager.bindTexture(staticTexture);
        GlStateManager.color(1, 1, 1, 1);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0, 1);
        GL11.glVertex2i(0, 0);
        GL11.glTexCoord2f(0, 0);
        GL11.glVertex2i(0, options.height());
        GL11.glTexCoord2f(1, 0);
        GL11.glVertex2i(options.width(), options.height());
        GL11.glTexCoord2f(1, 1);
        GL11.glVertex2i(options.width(), 0);
        GL11.glEnd();
    }

    /** Draws molecule gallery layout labels. */
    private void labels(MediaLayout.Cell cell) {
        FontRenderer font = PixelFont.renderer();
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.enableTexture2D();
        GlStateManager.enableAlpha();
        int primary = textColor();
        for (MediaLayout.Label label : cell.labels()) {
            float cursor = cell.left() + (cell.width() - label.width() * label.scale()) / 2;
            label.line().draw(font, cursor, label.top(), label.scale(), primary);
        }
    }

    /** Determines text color contrast based on background luminance. */
    private int textColor() {
        if (options.background() == null) {
            return 0xFF37372F;
        }
        int color = options.background();
        return (.2126 * (color >> 16 & 255) + .7152 * (color >> 8 & 255) + .0722 * (color & 255)) < 115
                ? 0xFFF1EDE2 : 0xFF37372F;
    }

    /** Resolves the display name for a molecule definition. */
    private static String name(MoleculeDef definition) {
        if (definition.name() != null) {
            return TextFormatting.getTextWithoutFormattingCodes(io.github.wurtzitane.avogadro.item.MoleculeItems.displayName(definition));
        }
        String key = "molecule." + definition.id().getNamespace() + "." + definition.id().getPath().replace('/', '.');
        if (I18n.hasKey(key)) {
            return TextFormatting.getTextWithoutFormattingCodes(I18n.format(key));
        }
        String path = definition.id().getPath();
        StringBuilder result = new StringBuilder();
        for (String word : path.substring(path.lastIndexOf('/') + 1).replace('_', ' ').replace('-', ' ').split(" +")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
        }
        return result.isEmpty() ? path : result.toString();
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
        if (preparation != null) {
            preparation.cancel(true);
        }
        if (processingPixels != null && processingClaim != null && processingClaim.compareAndSet(false, true)) {
            pixelPool.release(processingPixels);
        }
        if (staticPixels != null) {
            pixelPool.release(staticPixels);
            staticPixels = null;
        }
        if (copyLease != null) {
            pixelPool.release(copyLease);
            copyLease = null;
        }
        if (modelCursor != null) {
            modelCursor.close();
            modelCursor = null;
        }
        if (atomCursor != null) {
            atomCursor.close();
            atomCursor = null;
        }
        upload = null;
        artwork = null;
        if (atomRenderer != null) {
            atomRenderer.close();
        }
        for (int i = 0; i < readBuffers.length; i++) {
            if (fences[i] != 0) {
                deleteFence(fences[i]);
            }
            if (readBuffers[i] != 0) {
                GL15.glDeleteBuffers(readBuffers[i]);
            }
            fences[i] = 0;
            readBuffers[i] = 0;
        }
        recycled.clear();
        pixelPool.close();
        if (framebuffer != 0) {
            OpenGlHelper.glDeleteFramebuffers(framebuffer);
        }
        if (depthBuffer != 0) {
            OpenGlHelper.glDeleteRenderbuffers(depthBuffer);
        }
        if (colorTexture != 0) {
            GL11.glDeleteTextures(colorTexture);
        }
        if (staticTexture != 0) {
            GL11.glDeleteTextures(staticTexture);
        }
        framebuffer = depthBuffer = colorTexture = staticTexture = 0;
        if (pixels != null) {
            MemoryUtil.memFree(pixels);
        }
        pixels = null;
    }

    /** Creates an OpenGL sync fence object. */
    private static long fence() {
        return GL.getCapabilities().OpenGL32 ? GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
                : ARBSync.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    }

    /** Waits for an OpenGL sync fence client-side. */
    private static int waitFence(long fence) {
        return GL.getCapabilities().OpenGL32 ? GL32.glClientWaitSync(fence, 0, 0)
                : ARBSync.glClientWaitSync(fence, 0, 0);
    }

    /** Deletes an OpenGL sync fence object. */
    private static void deleteFence(long fence) {
        if (GL.getCapabilities().OpenGL32) {
            GL32.glDeleteSync(fence);
        } else {
            ARBSync.glDeleteSync(fence);
        }
    }

    /** Raw GL and the small subset of cached state changed by FontRenderer are restored together. */
    private static final class GraphicsScope implements AutoCloseable {
        private final int matrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        private final boolean separateFramebuffers = GL.getCapabilities().OpenGL30 || GL.getCapabilities().GL_ARB_framebuffer_object;
        private final int drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        private final int readFramebuffer = separateFramebuffers ? GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING) : drawFramebuffer;
        private final int renderbuffer = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
        private final int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        private final int packBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        private final int unpackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        private final int arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        private final int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        private final boolean alpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        private final float[] color = new float[4];
        private final int texture;
        private final boolean textureEnabled;
        private final MoleculeRenderer.StateScope renderScope;

        /** Constructs a new {@link GraphicsScope} capturing current OpenGL states. */
        GraphicsScope() {
            GL11.glGetFloatv(GL11.GL_CURRENT_COLOR, color);
            GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
            texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            textureEnabled = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
            renderScope = MoleculeRenderer.exportScope();
            GL11.glPushClientAttrib(GL11.GL_CLIENT_ALL_ATTRIB_BITS);
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPushMatrix();
            GL11.glMatrixMode(GL11.GL_TEXTURE);
            GL11.glPushMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPushMatrix();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void close() {
            GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
            GlStateManager.bindTexture(texture);
            if (textureEnabled) {
                GlStateManager.enableTexture2D();
            } else {
                GlStateManager.disableTexture2D();
            }
            if (alpha) {
                GlStateManager.enableAlpha();
            } else {
                GlStateManager.disableAlpha();
            }
            GlStateManager.color(color[0], color[1], color[2], color[3]);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_TEXTURE);
            GL11.glPopMatrix();
            GL11.glMatrixMode(matrixMode);
            GL11.glPopClientAttrib();
            // Read/draw-buffer selections belong to the framebuffer. Restore its
            // binding before the attribute stack restores those selections.
            if (separateFramebuffers) {
                OpenGlHelper.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
                OpenGlHelper.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
            } else {
                OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, drawFramebuffer);
            }
            renderScope.close();
            OpenGlHelper.glBindRenderbuffer(OpenGlHelper.GL_RENDERBUFFER, renderbuffer);
            GL20.glUseProgram(program);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, packBuffer);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpackBuffer);
            GL15.glBindBuffer(GL21.GL_ARRAY_BUFFER, arrayBuffer);
            GlStateManager.setActiveTexture(activeTexture);
        }
    }
}
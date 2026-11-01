package io.github.wurtzitane.avogadro.client;

import io.github.wurtzitane.avogadro.Avogadro;
import io.github.wurtzitane.avogadro.molecule.MoleculeStructure;
import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.opengl.*;

import java.nio.ByteBuffer;

/**
 * Manages fair, lazy full-geometry animation previews with complete-rotation admission and memory limits.
 * <p>Fair, lazy full-geometry previews with complete-rotation admission and a 64 MiB limit.
 */
final class MoleculeAnimationCache {

    /** Total number of pre-rendered frames cached per molecule rotation animation. */
    static final int FRAMES = 120;

    /** Maximum memory budget in bytes allocated for animation cache textures and framebuffers (64 MiB). */
    private static final long MAX_BYTES = 64L * 1024L * 1024L;

    /** Fixed-size array holding active molecule animation cache entries. */
    private static final Entry[] entries = new Entry[64];

    /** Scratch framebuffer target pool used for offscreen texture rendering. */
    private static final Scratch[] scratch = new Scratch[4];

    /** Stored OpenGL capabilities reference used to validate context continuity. */
    private static GLCapabilities context;

    /** Current total byte memory consumption, logical clock timestamp, reserved bytes, and render frame counter. */
    private static long bytes, clock, reservedBytes, renderFrame;

    /** Cursor index pointing to the next entry to evaluate for background generation. */
    private static int pendingCursor;

    /** Flag indicating whether the cache is currently within an active render frame block. */
    private static boolean inFrame;

    /** Number of frames required to retain an entry before considering it for eviction. */
    private static final int RETAIN_FRAMES = 120;

    /** Maximum supported texture units and maximum texture size limits queried from OpenGL. */
    private static int textureUnits, maxTextureSize;

    /** Flag indicating whether pre-rendered animation caching has failed and become unavailable. */
    private static boolean unavailable;

    /** Private constructor to prevent instantiation of utility class. */
    private MoleculeAnimationCache() {}

    /** Begins a new animation rendering frame. */
    static void beginFrame() {
        inFrame = true;
        renderFrame++;
        for (Entry entry : entries) {
            if (entry != null) {
                entry.pendingFrame = -1;
            }
        }
    }

    /**
     * Ends the current animation frame and processes pending preview captures.
     * <p>Capture after visible draws, so the first preview cannot monopolize generation.
     */
    static void endFrame() {
        try {
            if (!inFrame || unavailable || context == null) {
                return;
            }
            checkContext();
            for (int visited = 0; visited < entries.length; visited++) {
                int slot = (pendingCursor + visited) % entries.length;
                Entry entry = entries[slot];
                if (entry == null || entry.pendingFrame < 0) {
                    continue;
                }
                if (entry.requestedAt != renderFrame) {
                    entry.pendingFrame = -1;
                    continue;
                }
                // Visible fallback draws already advance uploads within the shared budget.
                if (GpuMoleculeCache.find(entry.molecule, entry.cubic, entry.colors) == null) {
                    continue;
                }
                if (!FrameWorkBudget.tryAnimation()) {
                    break;
                }
                int index = entry.pendingFrame;
                entry.pendingFrame = -1;
                pendingCursor = (slot + 1) % entries.length;
                generate(entry, index, entry.colors);
                break;
            }
        } finally {
            inFrame = false;
        }
    }

    /**
     * Calculates the animation frame index corresponding to a yaw rotation angle.
     *
     * @param yaw yaw rotation angle in degrees
     * @return corresponding animation frame index
     */
    static int frame(float yaw) {
        return Math.floorMod(Math.round(yaw / 360f * FRAMES), FRAMES);
    }

    /**
     * Calculates physical resolution accounting for GUI pixel scale.
     *
     * @param logical logical size in pixels
     * @param scale   GUI scale factor
     * @return physical texture resolution in pixels
     */
    static int physicalResolution(int logical, float scale) {
        float pixels = logical * scale;
        double nearest = Math.rint(pixels);
        // GUI matrix roundoff must not turn a native-size capture into a larger
        // texture that is then resampled. Genuine fractional scales still round up.
        return (int) (Math.abs(pixels - nearest) <= 4 * Math.ulp(pixels) ? nearest : Math.ceil(pixels));
    }

    /**
     * Renders or queues a pre-rendered molecule animation frame.
     *
     * @param molecule molecule structure
     * @param x        x coordinate position
     * @param y        y coordinate position
     * @param size     render size
     * @param yaw      yaw rotation angle
     * @param cubic    whether cubic crystal rendering is enabled
     * @param palette  element palette snapshot
     * @return {@code true} if drawn successfully, {@code false} otherwise
     */
    static boolean draw(MoleculeStructure molecule, int x, int y, int size, float yaw, boolean cubic, ElementPalette.Snapshot palette) {
        checkContext();
        if (unavailable || !OpenGlHelper.framebufferSupported || !Float.isFinite(GuiClip.pixelScale)) {
            return false;
        }
        int logical = size + 24;
        int resolution = physicalResolution(logical, GuiClip.pixelScale);
        long cost = (long) resolution * resolution * 4;
        if (resolution <= 0 || cost * (FRAMES + scratch.length) > MAX_BYTES || resolution > maxTextureSize) {
            return false;
        }
        Entry entry = null;
        for (Entry candidate : entries) {
            if (candidate != null && candidate.molecule == molecule && candidate.cubic == cubic
                    && candidate.palette == palette.revision() && candidate.resolution == resolution && candidate.size == size) {
                entry = candidate;
                break;
            }
        }
        if (entry == null) {
            long reservation = cost * (FRAMES + scratch.length);
            int slot = admit(reservation);
            if (slot < 0) {
                return false;
            }
            entry = new Entry(molecule, cubic, palette, resolution, size, reservation);
            entries[slot] = entry;
            reservedBytes += reservation;
        }
        entry.used = ++clock;
        entry.requestedAt = renderFrame;
        int frame = frame(yaw);
        if (entry.textures[frame] == 0) {
            if (inFrame) {
                entry.pendingFrame = frame;
                return false;
            }
            if (!GpuMoleculeCache.prepare(molecule, cubic, palette) || !FrameWorkBudget.tryAnimation()) {
                return false;
            }
            if (!generate(entry, frame, palette)) {
                return false;
            }
        }
        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int mode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glMatrixMode(GL11.GL_TEXTURE);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        try {
            GL20.glUseProgram(0);
            disableTextures();
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, entry.textures[frame]);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_FOG);
            GL11.glDisable(GL11.GL_COLOR_LOGIC_OP);
            GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
            GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glColor4f(1, 1, 1, 1);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glTexCoord2f(0, 1);
            GL11.glVertex3f(x - 12, y - 12, 100);
            GL11.glTexCoord2f(0, 0);
            GL11.glVertex3f(x - 12, y + size + 12, 100);
            GL11.glTexCoord2f(1, 0);
            GL11.glVertex3f(x + size + 12, y + size + 12, 100);
            GL11.glTexCoord2f(1, 1);
            GL11.glVertex3f(x + size + 12, y - 12, 100);
            GL11.glEnd();
        } finally {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glMatrixMode(GL11.GL_TEXTURE);
            GL11.glPopMatrix();
            GL11.glMatrixMode(mode);
            GL20.glUseProgram(program);
        }
        return true;
    }

    /** Generates an animation frame capture. */
    private static boolean generate(Entry entry, int frame, ElementPalette.Snapshot palette) {
        long started = System.nanoTime();
        try {
            capture(entry, frame, palette);
            return true;
        } catch (RuntimeException failure) {
            clear();
            unavailable = true;
            Avogadro.LOGGER.warn("Pre-rendered molecule previews unavailable; using live geometry: {}", failure.getMessage());
            return false;
        } finally {
            FrameWorkBudget.recordAnimation(System.nanoTime() - started);
        }
    }

    /** Evaluates whether an animation entry reservation can be admitted. */
    private static int admit(long reservation) {
        // Reserve a full rotation before allocating its first texture. Partial rotations
        // that evict each other's early angles never warm up, however long they are shown.
        long available = MAX_BYTES - reservedBytes;
        boolean hasSlot = false;
        for (Entry entry : entries) {
            if (entry == null) {
                hasSlot = true;
            } else if (renderFrame - entry.requestedAt >= RETAIN_FRAMES) {
                available += entry.reservation;
                hasSlot = true;
            }
        }
        // A rejected large preview must not discard idle entries unless their
        // reclaimed space can actually admit the requested rotation.
        if (!hasSlot || available < reservation) {
            return -1;
        }
        while (true) {
            int free = -1, victim = -1;
            for (int i = 0; i < entries.length; i++) {
                Entry candidate = entries[i];
                if (candidate == null) {
                    free = i;
                    continue;
                }
                if (renderFrame - candidate.requestedAt >= RETAIN_FRAMES &&
                        (victim < 0 || candidate.used < entries[victim].used)) {
                    victim = i;
                }
            }
            if (free >= 0 && reservedBytes + reservation <= MAX_BYTES) {
                return free;
            }
            // Keep the working set stable; excess previews use complete live geometry.
            if (victim < 0) {
                return -1;
            }
            evict(victim);
        }
    }

    /** Captures an offscreen rendering frame into a texture. */
    private static void capture(Entry entry, int frame, ElementPalette.Snapshot palette) {
        int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        boolean separate = context.OpenGL30 || context.GL_ARB_framebuffer_object;
        int read = separate ? GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING) : draw;
        int renderbuffer = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
        int mode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        int unpack = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        int texture = 0;
        Scratch captureTarget = null;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        try {
            Scratch target = target(entry.resolution);
            captureTarget = target;
            makeRoom((long) entry.resolution * entry.resolution * 4, target);
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, target.framebuffer);
            texture = GL11.glGenTextures();
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, entry.resolution, entry.resolution, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            OpenGlHelper.glFramebufferTexture2D(OpenGlHelper.GL_FRAMEBUFFER, OpenGlHelper.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
            if (OpenGlHelper.glCheckFramebufferStatus(OpenGlHelper.GL_FRAMEBUFFER) != OpenGlHelper.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("Cannot create molecule animation framebuffer");
            }
            GL11.glViewport(0, 0, entry.resolution, entry.resolution);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_FOG);
            GL11.glDisable(GL11.GL_COLOR_LOGIC_OP);
            GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
            GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
            GL11.glDepthRange(0, 1);
            for (int plane = 0; plane < 6; plane++) {
                GL11.glDisable(GL11.GL_CLIP_PLANE0 + plane);
            }
            disableTextures();
            GL11.glColorMask(true, true, true, true);
            GL11.glDepthMask(true);
            GL11.glClearColor(0, 0, 0, 0);
            GL11.glClearDepth(1);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glLoadIdentity();
            GL11.glOrtho(0, entry.size + 24, entry.size + 24, 0, -2000, 2000);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glLoadIdentity();
            MoleculeRenderer.renderForExport(entry.molecule, 0, 0, entry.size + 24, entry.size + 24, entry.size,
                    frame * 360f / FRAMES, entry.cubic, palette);
            Frame cached = new Frame(entry, frame, texture);
            entry.textures[frame] = texture;
            entry.frames[frame] = cached;
            bytes += cached.cost;
            texture = 0;
        } finally {
            if (captureTarget != null) {
                OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, captureTarget.framebuffer);
                OpenGlHelper.glFramebufferTexture2D(OpenGlHelper.GL_FRAMEBUFFER, OpenGlHelper.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, 0, 0);
            }
            if (texture != 0) {
                GL11.glDeleteTextures(texture);
            }
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(mode);
            if (separate) {
                OpenGlHelper.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
                OpenGlHelper.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
            } else {
                OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, draw);
            }
            GL11.glPopAttrib();
            OpenGlHelper.glBindRenderbuffer(OpenGlHelper.GL_RENDERBUFFER, renderbuffer);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpack);
        }
    }

    /** Disables all active texture units. */
    private static void disableTextures() {
        for (int unit = 0; unit < textureUnits; unit++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_TEXTURE_GEN_S);
            GL11.glDisable(GL11.GL_TEXTURE_GEN_T);
            GL11.glDisable(GL11.GL_TEXTURE_GEN_R);
            GL11.glDisable(GL11.GL_TEXTURE_GEN_Q);
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    /** Verifies OpenGL context validity. */
    private static void checkContext() {
        GLCapabilities current = GL.getCapabilities();
        if (context == current) {
            return;
        }
        java.util.Arrays.fill(entries, null);
        java.util.Arrays.fill(scratch, null);
        bytes = clock = reservedBytes = 0;
        pendingCursor = 0;
        context = current;
        unavailable = false;
        textureUnits = GL11.glGetInteger(GL13.GL_MAX_TEXTURE_UNITS);
        maxTextureSize = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
    }

    /** Clears all animation cache entries and scratch targets. */
    static void clear() {
        checkContext();
        for (int i = 0; i < entries.length; i++) {
            evict(i);
        }
        for (int i = 0; i < scratch.length; i++) {
            deleteScratch(i);
        }
        unavailable = false;
        pendingCursor = 0;
    }

    /** Evicts an animation entry at the specified slot index. */
    private static void evict(int i) {
        if (i < 0 || entries[i] == null) {
            return;
        }
        Entry entry = entries[i];
        entries[i] = null;
        reservedBytes -= entry.reservation;
        for (Frame frame : entry.frames) {
            if (frame != null) {
                deleteFrame(frame);
            }
        }
        // Scratch memory must belong to a still-admitted resolution for reservations
        // to bound actual storage, even after many size changes.
        for (int slot = 0; slot < scratch.length; slot++) {
            Scratch target = scratch[slot];
            if (target == null) {
                continue;
            }
            boolean retained = false;
            for (Entry other : entries) {
                if (other != null && other.resolution == target.resolution) {
                    retained = true;
                    break;
                }
            }
            if (!retained) {
                deleteScratch(slot);
            }
        }
    }

    /**
     * Represents a cached molecule animation entry.
     */
    private static final class Entry {
        final MoleculeStructure molecule;
        final boolean cubic;
        final long palette;
        final int resolution, size;
        final ElementPalette.Snapshot colors;
        final long reservation;
        final int[] textures = new int[FRAMES];
        final Frame[] frames = new Frame[FRAMES];
        long used, requestedAt;
        int pendingFrame = -1;

        /**
         * Constructs a new animation entry.
         *
         * @param molecule    molecule structure
         * @param cubic       whether cubic rendering is enabled
         * @param palette     element palette snapshot
         * @param resolution  physical render resolution
         * @param size        logical render size
         * @param reservation memory reservation cost
         */
        Entry(MoleculeStructure molecule, boolean cubic, ElementPalette.Snapshot palette, int resolution, int size, long reservation) {
            this.molecule = molecule;
            this.cubic = cubic;
            this.palette = palette.revision();
            this.colors = palette;
            this.resolution = resolution;
            this.size = size;
            this.reservation = reservation;
        }
    }

    /** Deletes an individual cached frame texture. */
    private static void deleteFrame(Frame frame) {
        GL11.glDeleteTextures(frame.texture);
        bytes -= frame.cost;
        frame.entry.textures[frame.index] = 0;
        frame.entry.frames[frame.index] = null;
    }

    /** Frees cache space until the requested cost fits within limits. */
    private static void makeRoom(long cost, Scratch keep) {
        while (bytes + cost > MAX_BYTES) {
            int slot = -1;
            for (int i = 0; i < scratch.length; i++) {
                if (scratch[i] != null && scratch[i] != keep
                        && (slot < 0 || scratch[i].used < scratch[slot].used)) {
                    slot = i;
                }
            }
            if (slot < 0) {
                throw new IllegalStateException("Animation cache budget exceeded");
            }
            deleteScratch(slot);
        }
    }

    /** Retrieves or allocates a scratch target for the given resolution. */
    private static Scratch target(int resolution) {
        int slot = 0;
        for (int i = 0; i < scratch.length; i++) {
            if (scratch[i] != null && scratch[i].resolution == resolution) {
                scratch[i].used = ++clock;
                return scratch[i];
            }
            if (scratch[slot] != null && (scratch[i] == null || scratch[i].used < scratch[slot].used)) {
                slot = i;
            }
        }
        deleteScratch(slot);
        long cost = (long) resolution * resolution * 4;
        makeRoom(cost * 2, null);
        Scratch target = new Scratch(resolution);
        scratch[slot] = target;
        try {
            target.framebuffer = OpenGlHelper.glGenFramebuffers();
            target.depth = OpenGlHelper.glGenRenderbuffers();
            OpenGlHelper.glBindRenderbuffer(OpenGlHelper.GL_RENDERBUFFER, target.depth);
            OpenGlHelper.glRenderbufferStorage(OpenGlHelper.GL_RENDERBUFFER, GL14.GL_DEPTH_COMPONENT24, resolution, resolution);
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, target.framebuffer);
            OpenGlHelper.glFramebufferRenderbuffer(OpenGlHelper.GL_FRAMEBUFFER, OpenGlHelper.GL_DEPTH_ATTACHMENT, OpenGlHelper.GL_RENDERBUFFER, target.depth);
            bytes += cost;
            target.accounted = true;
            target.used = ++clock;
            return target;
        } catch (RuntimeException failure) {
            deleteScratch(slot);
            throw failure;
        }
    }

    /** Deletes a scratch target at the specified slot index. */
    private static void deleteScratch(int slot) {
        Scratch target = scratch[slot];
        if (target == null) {
            return;
        }
        scratch[slot] = null;
        if (target.framebuffer != 0) {
            OpenGlHelper.glDeleteFramebuffers(target.framebuffer);
        }
        if (target.depth != 0) {
            OpenGlHelper.glDeleteRenderbuffers(target.depth);
        }
        if (target.accounted) {
            bytes -= (long) target.resolution * target.resolution * 4;
        }
    }

    /**
     * Represents offscreen scratch framebuffer targets.
     */
    private static final class Scratch {
        final int resolution;
        int framebuffer, depth;
        long used;
        boolean accounted;

        /**
         * Constructs a new scratch target.
         *
         * @param resolution target resolution
         */
        Scratch(int resolution) {
            this.resolution = resolution;
        }
    }

    /**
     * Represents a cached animation frame texture.
     */
    private static final class Frame {
        final Entry entry;
        final int index, texture;
        final long cost;

        /**
         * Constructs a new animation frame record.
         *
         * @parent entry   parent animation entry
         * @param index   frame index
         * @param texture OpenGL texture ID
         */
        Frame(Entry entry, int index, int texture) {
            this.entry = entry;
            this.index = index;
            this.texture = texture;
            cost = (long) entry.resolution * entry.resolution * 4;
        }
    }
}
package io.github.wurtzitane.avogadro.client;

import io.github.wurtzitane.avogadro.Config;
import io.github.wurtzitane.avogadro.molecule.MoleculeStructure;
import io.github.wurtzitane.avogadro.molecule.MoleculeStructure.Atom;
import net.minecraft.client.renderer.BufferBuilder;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Comparator;

/**
 * Handles the rendering, tessellation, geometric transformation, and animation calculations
 * for chemical molecules, atoms, and bonds in 3D space.
 */
public final class MoleculeRenderer {

    /** Number of longitudinal slices used for spherical atom tessellation. */
    private static final int SPHERE_SLICES = 14;

    /** Number of latitudinal stacks used for spherical atom tessellation. */
    private static final int SPHERE_STACKS = 10;

    /** Number of radial segments used for cylindrical bond tessellation. */
    private static final int CYL_SEGMENTS = 10;

    /** Precalculated latitude angles for sphere mesh generation. */
    private static final Angle[] SPHERE_LATITUDES = arc(SPHERE_STACKS, Math.PI);

    /** Precalculated longitude angles for sphere mesh generation. */
    private static final Angle[] SPHERE_LONGITUDES = arc(SPHERE_SLICES, 2.0 * Math.PI);

    /** Precalculated angles for smooth cylinder bonds. */
    private static final Angle[] CYLINDER_ANGLES = arc(CYL_SEGMENTS, 2.0 * Math.PI);

    /** Precalculated angles for cubic/square bonds. */
    private static final Angle[] SQUARE_ANGLES = arc(4, 2.0 * Math.PI);

    /** Precalculated normals for cubic bond faces. */
    private static final Angle[] SQUARE_NORMALS = squareNormals();

    /** Direction vector representing the primary light source for per-vertex diffuse lighting. */
    private static final Vector3f LIGHT_DIR = new Vector3f(-0.577f, 0.577f, -0.577f);

    /** Ambient lighting intensity factor applied to all rendered geometry. */
    private static final float AMBIENT = 0.35f;

    /** Precalculated vertex data array for spherical atom geometry. */
    private static final float[] SPHERE_VERTICES = sphereVertices();

    /** Red color channel component for bond rendering. */
    private static final float BOND_R = 0xCC / 255f;

    /** Green color channel component for bond rendering. */
    private static final float BOND_G = 0xCC / 255f;

    /** Blue color channel component for bond rendering. */
    private static final float BOND_B = 0xCC / 255f;

    /** Scaling factor applied to atom radii to fit them neatly within bonds. */
    private static final float SPHERE_SCALE = 0.9f;

    /** Radius thickness for single bonds. */
    private static final float SINGLE_BOND_RADIUS = 0.15f;

    /** Radius thickness for multiple (double/triple) bonds. */
    private static final float MULTI_BOND_RADIUS = 0.10f;

    /** Offset spacing distance between parallel cylinders in multiple bonds. */
    private static final float MULTI_BOND_GAP = 0.14f;

    /** Reusable matrix instance dedicated to calculating drawing transformations. */
    private static final Matrix4f DRAW_TRANSFORM = new Matrix4f();

    /** Volatile flag indicating whether rendering and geometry caches need to be cleared/invalidated. */
    private static volatile boolean cachesInvalid;

    /** Nesting depth counter tracking active OpenGL attribute state pushes. */
    private static int stateDepth;

    /** Nesting depth counter tracking active media export scopes. */
    private static int exportDepth;

    /** Private constructor to prevent instantiation of utility class. */
    private MoleculeRenderer() {}

    /**
     * Calculates continuous rotation angle using the same wall-clock period as the original renderer.
     *
     * @return current rotation angle in degrees
     */
    public static float rotationAngle() {
        double period = Config.SPIN_SECONDS.get() * 1000.0;
        return (float) ((System.currentTimeMillis() % (long) period) / period * 360.0 *
                Config.ROTATION_DIRECTION.get().sign());
    }

    /**
     * Determines automatic orientation at a deterministic time using current appearance settings.
     *
     * @param elapsedSeconds elapsed time in seconds
     * @return computed rotation angle in degrees
     */
    public static float rotationAngle(double elapsedSeconds) {
        return rotationAngle(elapsedSeconds, Config.SPIN_SECONDS.get(), Config.ROTATION_DIRECTION.get());
    }

    /**
     * Calculates rotation angle with explicit settings allowing exports to override appearance parameters.
     *
     * @param elapsedSeconds       elapsed time in seconds
     * @param secondsPerRotation   duration of a full rotation cycle in seconds
     * @param direction            rotation direction configuration
     * @return computed rotation angle in degrees
     */
    public static float rotationAngle(double elapsedSeconds, double secondsPerRotation,
                                      Config.RotationDirection direction) {
        if (!Double.isFinite(elapsedSeconds) || !Double.isFinite(secondsPerRotation) || secondsPerRotation <= 0) {
            throw new IllegalArgumentException("Rotation time must be finite and the period must be positive.");
        }
        if (direction == null) throw new IllegalArgumentException("Rotation direction is required.");
        return (float) (elapsedSeconds % secondsPerRotation / secondsPerRotation * 360.0 * direction.sign());
    }

    /**
     * Renders a molecule structure at specified screen coordinates with default pitch.
     *
     * @param mol   the molecule structure to render
     * @param x     x-coordinate
     * @param y     y-coordinate
     * @param size  rendering size
     * @param angle rotation yaw angle
     */
    public static void render(MoleculeStructure mol, int x, int y, int size, float angle) {
        render(mol, x, y, size, angle, 15f);
    }

    /**
     * Renders a molecule structure at specified screen coordinates with explicit yaw and pitch,
     * preserving default tooltip padding.
     *
     * @param mol   the molecule structure to render
     * @param x     x-coordinate
     * @param y     y-coordinate
     * @param size  rendering size
     * @param yaw   rotation yaw angle
     * @param pitch rotation pitch angle
     */
    public static void render(MoleculeStructure mol, int x, int y, int size, float yaw, float pitch) {
        renderClipped(mol, x, y, size, yaw, pitch, x - 12, y - 6, x + size + 12, y + size + 6);
    }

    /**
     * Renders a molecule inside a specific viewport container bounds.
     *
     * @param molecule  the molecule structure
     * @param left      left boundary
     * @param top       top boundary
     * @param width     viewport width
     * @param height    viewport height
     * @param imageSize size of the inner rendered image
     * @param yaw       rotation yaw angle
     * @param pitch     rotation pitch angle
     */
    public static void renderInViewport(MoleculeStructure molecule, int left, int top, int width, int height,
                                        int imageSize, float yaw, float pitch) {
        renderClipped(molecule, left + (width - imageSize) / 2, top + (height - imageSize) / 2,
                imageSize, yaw, pitch, left, top, left + width, top + height);
    }

    /**
     * Renders a molecule inside a specific viewport container with translation and zoom scaling.
     *
     * @param molecule  the molecule structure
     * @param left      left boundary
     * @param top       top boundary
     * @param width     viewport width
     * @param height    viewport height
     * @param imageSize size of the inner rendered image
     * @param yaw       rotation yaw angle
     * @param pitch     rotation pitch angle
     * @param panX      horizontal panning offset
     * @param panY      vertical panning offset
     * @param zoom      zoom scale factor
     */
    public static void renderInViewport(MoleculeStructure molecule, int left, int top, int width, int height,
                                        int imageSize, float yaw, float pitch, float panX, float panY, float zoom) {
        if (!Float.isFinite(panX) || !Float.isFinite(panY) || !Float.isFinite(zoom) || zoom <= 0) return;
        renderClipped(molecule, left + (width - imageSize) / 2, top + (height - imageSize) / 2,
                imageSize, yaw, pitch, left, top, left + width, top + height, Config.CUBIC_ATOMS.get(), panX, panY,
                zoom);
    }

    /**
     * Renders a molecule for pixel-coordinate media capture with local appearance overrides.
     *
     * @param molecule  the molecule structure
     * @param left      left screen coordinate
     * @param top       top screen coordinate
     * @param width     target area width
     * @param height    target area height
     * @param imageSize size of the rendered image
     * @param yaw       rotation yaw angle
     * @param cubic     whether to render atoms as cubes
     */
    public static void renderForExport(MoleculeStructure molecule, int left, int top, int width, int height,
                                       int imageSize, float yaw, boolean cubic) {
        renderForExport(molecule, left, top, width, height, imageSize, yaw, cubic, ElementPalette.snapshot());
    }

    /**
     * Renders a molecule for export using a specific color palette snapshot.
     *
     * @param molecule  the molecule structure
     * @param left      left screen coordinate
     * @param top       top screen coordinate
     * @param width     target area width
     * @param height    target area height
     * @param imageSize size of the rendered image
     * @param yaw       rotation yaw angle
     * @param cubic     whether to render atoms as cubes
     * @param palette   the element palette snapshot to use for coloring
     */
    public static void renderForExport(MoleculeStructure molecule, int left, int top, int width, int height,
                                       int imageSize, float yaw, boolean cubic, ElementPalette.Snapshot palette) {
        renderClipped(molecule, left + (width - imageSize) / 2, top + (height - imageSize) / 2,
                imageSize, yaw, 15f, left, top, left + width, top + height, cubic, 0, 0, 1, palette, false, null, true);
    }

    /**
     * Initializes an export cursor for multi-tick geometry export rendering.
     *
     * @param molecule  the molecule structure
     * @param left      left boundary
     * @param top       top boundary
     * @param width     width boundary
     * @param height    height boundary
     * @param size      render size
     * @param yaw       yaw angle
     * @param cubic     whether atoms are cubic
     * @param palette   element palette snapshot
     * @return configured {@link ExportCursor} instance
     */
    public static ExportCursor beginExport(MoleculeStructure molecule, int left, int top, int width, int height,
                                           int size, float yaw, boolean cubic, ElementPalette.Snapshot palette) {
        checkCaches();
        GpuMoleculeCache.ExportCursor geometry = GpuMoleculeCache.beginExport(molecule, cubic, palette);
        return geometry == null ? null : new ExportCursor(geometry, molecule, left, top, width, height, size, yaw);
    }

    /**
     * Initializes an export cursor configured with rendering options.
     *
     * @param molecule the molecule structure
     * @param left     left boundary
     * @param top      top boundary
     * @param width    width boundary
     * @param height   height boundary
     * @param size     render size
     * @param yaw      yaw angle
     * @param options  molecule render options
     * @return configured {@link ExportCursor} instance
     */
    public static ExportCursor beginExport(MoleculeStructure molecule, int left, int top, int width, int height, int size, float yaw,
                                           io.github.wurtzitane.avogadro.api.client.MoleculeRenderOptions options) {
        checkCaches();
        MoleculeStyle style = new MoleculeStyle(options.atomScale(), options.bondScale());
        size = MoleculeViewport.fitSize(molecule, size, width, height, options.cubic(), style);
        GpuMoleculeCache.ExportCursor geometry = GpuMoleculeCache.beginExport(molecule, options.cubic(), options.palette(), style);
        if (geometry == null) return null;
        ExportCursor result = new ExportCursor(geometry, molecule, left, top, width, height, size, yaw);
        transform(result.transform, molecule, left + (width - size) / 2, top + (height - size) / 2, size, yaw, options.pitch(), options.roll());
        if (options.panX() != 0 || options.panY() != 0 || options.zoom() != 1) {
            float cx = left + width / 2f, cy = top + height / 2f;
            result.transform.set(new Matrix4f().translation(cx + options.panX(), cy + options.panY(), 0).scale(options.zoom(), options.zoom(), 1)
                    .translate(-cx, -cy, 0).mul(result.transform));
        }
        result.background = options.background();
        return result;
    }

    /**
     * Cursor managing step-by-step frame rendering for batch exports.
     */
    public static final class ExportCursor implements AutoCloseable {
        private final GpuMoleculeCache.ExportCursor geometry;
        private final Matrix4f transform = new Matrix4f();
        private final int left, top, width, height;
        private boolean started, closed;
        private Integer background;

        ExportCursor(GpuMoleculeCache.ExportCursor geometry, MoleculeStructure molecule, int left, int top,
                     int width, int height, int size, float yaw) {
            this.geometry = geometry; this.left = left; this.top = top; this.width = width; this.height = height;
            MoleculeRenderer.transform(transform, molecule, left + (width - size) / 2, top + (height - size) / 2, size, yaw, 15f);
        }

        /**
         * Advances the export rendering sequence by one step.
         *
         * @return {@code true} if export rendering is complete, {@code false} otherwise
         */
        public boolean advance() {
            if (closed) return true;
            beginState();
            try {
                if (!GuiClip.intersect(left, top, left + width, top + height)) { close(); return true; }
                GL11.glDisable(GL11.GL_TEXTURE_2D); GL11.glDisable(GL11.GL_LIGHTING);
                GL11.glDisable(GL11.GL_CULL_FACE); GL11.glDisable(GL11.GL_ALPHA_TEST);
                GL11.glEnable(GL11.GL_DEPTH_TEST); GL11.glDepthFunc(GL11.GL_LEQUAL); GL11.glDepthMask(true);
                GL11.glEnable(GL11.GL_BLEND); GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                GL11.glShadeModel(GL11.GL_SMOOTH); GL11.glClearDepth(1);
                if (!started) {
                    if (background != null) {
                        int color = background; GL11.glDisable(GL11.GL_DEPTH_TEST);
                        GL11.glColor4f((color >> 16 & 255) / 255f, (color >> 8 & 255) / 255f, (color & 255) / 255f, (color >>> 24) / 255f);
                        GL11.glBegin(GL11.GL_QUADS); GL11.glVertex2i(left, top); GL11.glVertex2i(left, top + height);
                        GL11.glVertex2i(left + width, top + height); GL11.glVertex2i(left + width, top); GL11.glEnd();
                        GL11.glEnable(GL11.GL_DEPTH_TEST);
                    }
                    GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT); started = true;
                }
                boolean complete = geometry.advance(transform);
                if (complete) { GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT); close(); }
                return complete;
            } finally { endState(); }
        }

        @Override
        public void close() {
            if (!closed) { closed = true; geometry.close(); }
        }
    }

    private static void renderClipped(MoleculeStructure mol, int x, int y, int size, float yaw, float pitch,
                                      int clipLeft, int clipTop, int clipRight, int clipBottom) {
        renderClipped(mol, x, y, size, yaw, pitch, clipLeft, clipTop, clipRight, clipBottom, Config.CUBIC_ATOMS.get());
    }

    private static void renderClipped(MoleculeStructure mol, int x, int y, int size, float yaw, float pitch,
                                      int clipLeft, int clipTop, int clipRight, int clipBottom, boolean cubic) {
        renderClipped(mol, x, y, size, yaw, pitch, clipLeft, clipTop, clipRight, clipBottom, cubic, 0, 0, 1);
    }

    private static void renderClipped(MoleculeStructure mol, int x, int y, int size, float yaw, float pitch,
                                      int clipLeft, int clipTop, int clipRight, int clipBottom, boolean cubic,
                                      float panX, float panY, float zoom) {
        renderClipped(mol, x, y, size, yaw, pitch, clipLeft, clipTop, clipRight, clipBottom,
                cubic, panX, panY, zoom, ElementPalette.snapshot());
    }

    private static void renderClipped(MoleculeStructure mol, int x, int y, int size, float yaw, float pitch,
                                      int clipLeft, int clipTop, int clipRight, int clipBottom, boolean cubic,
                                      float panX, float panY, float zoom, ElementPalette.Snapshot palette) {
        renderClipped(mol, x, y, size, yaw, pitch, clipLeft, clipTop, clipRight, clipBottom, cubic, panX, panY, zoom,
                palette, true);
    }

    private static void renderClipped(MoleculeStructure mol, int x, int y, int size, float yaw, float pitch,
                                      int clipLeft, int clipTop, int clipRight, int clipBottom, boolean cubic,
                                      float panX, float panY, float zoom, ElementPalette.Snapshot palette,
                                      boolean preview) {
        renderClipped(mol, x, y, size, yaw, pitch, clipLeft, clipTop, clipRight, clipBottom, cubic, panX, panY, zoom,
                palette, preview, null);
    }

    static void renderCrystal(MoleculeStructure molecule, int left, int top, int width, int height, int size,
                              float yaw, float pitch, float panX, float panY, float zoom, CrystalOutline outline) {
        renderClipped(molecule, left + (width - size) / 2, top + (height - size) / 2, size, yaw, pitch, left, top,
                left + width, top + height,
                Config.CUBIC_ATOMS.get(), panX, panY, zoom, ElementPalette.snapshot(), false, outline);
    }

    private static void renderClipped(MoleculeStructure mol, int x, int y, int size, float yaw, float pitch,
                                      int clipLeft, int clipTop, int clipRight, int clipBottom, boolean cubic,
                                      float panX, float panY, float zoom, ElementPalette.Snapshot palette,
                                      boolean preview,
                                      CrystalOutline outline) {
        renderClipped(mol, x, y, size, yaw, pitch, clipLeft, clipTop, clipRight, clipBottom, cubic, panX, panY,
                zoom, palette, preview, outline, false);
    }

    private static void renderClipped(MoleculeStructure mol, int x, int y, int size, float yaw, float pitch,
                                      int clipLeft, int clipTop, int clipRight, int clipBottom, boolean cubic,
                                      float panX, float panY, float zoom, ElementPalette.Snapshot palette,
                                      boolean preview, CrystalOutline outline, boolean immediate) {
        renderClipped(mol, x, y, size, yaw, pitch, clipLeft, clipTop, clipRight, clipBottom, cubic,
                panX, panY, zoom, palette, preview, outline, immediate, MoleculeStyle.DEFAULT, 0);
    }

    private static void renderClipped(MoleculeStructure mol, int x, int y, int size, float yaw, float pitch,
                                      int clipLeft, int clipTop, int clipRight, int clipBottom, boolean cubic,
                                      float panX, float panY, float zoom, ElementPalette.Snapshot palette,
                                      boolean preview, CrystalOutline outline, boolean immediate,
                                      MoleculeStyle style, float roll) {
        if (mol.atoms().isEmpty() || size <= 0) {
            return;
        }
        checkCaches();
        // Only raw GL state is changed here. The attribute stack restores it without
        // invalidating Minecraft's GlStateManager cache. All geometry is the original
        // upstream tessellation and per-vertex lighting, stored in persistent GPU buffers.
        beginState();
        boolean camera = panX != 0 || panY != 0 || zoom != 1;
        boolean matrixPushed = false;
        int matrixMode = 0;
        try {
            if (!GuiClip.intersect(clipLeft, clipTop, clipRight, clipBottom)) return;
            if (preview && !camera && pitch == 15f && Config.RENDERING_MODE.get() == Config.RenderingMode.PRERENDERED &&
                    MoleculeAnimationCache.draw(mol, x, y, size, yaw, cubic, palette))
                return;
            if (camera) {
                matrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glPushMatrix();
                matrixPushed = true;
                float centerX = (clipLeft + clipRight) / 2f, centerY = (clipTop + clipBottom) / 2f;
                GL11.glTranslatef(centerX + panX, centerY + panY, 0);
                GL11.glScalef(zoom, zoom, 1);
                GL11.glTranslatef(-centerX, -centerY, 0);
            }
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glDepthMask(true);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glShadeModel(GL11.GL_SMOOTH);
            GL11.glClearDepth(1.0);
            // The clipping rectangle bounds both depth clears to this molecule.
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
            transform(DRAW_TRANSFORM, mol, x, y, size, yaw, pitch, roll);
            boolean ready = GpuMoleculeCache.draw(mol, DRAW_TRANSFORM, cubic, palette, immediate, style);
            if (ready && outline != null) outline.draw(DRAW_TRANSFORM);
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
        } finally {
            if (matrixPushed) {
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glPopMatrix();
                GL11.glMatrixMode(matrixMode);
            }
            endState();
        }
    }

    /** Explicit per-view presentation; never enters the fixed-camera animation cache. */
    public static void render(MoleculeStructure molecule, int x, int y, int width, int height,
                              io.github.wurtzitane.avogadro.api.client.MoleculeRenderOptions options,
                              double elapsedSeconds) {
        java.util.Objects.requireNonNull(options);
        if (width <= 0 || height <= 0) return;
        MoleculeStyle style = new MoleculeStyle(options.atomScale(), options.bondScale());
        int size = MoleculeViewport.fitSize(molecule, Math.min(width, height), width, height, options.cubic(), style);
        renderStyled(molecule, x, y, width, height, size, options.yawAt(elapsedSeconds), options, false);
    }

    /**
     * Renders a molecule for export with specific render options.
     *
     * @param molecule the molecule structure
     * @param x        x screen coordinate
     * @param y        y screen coordinate
     * @param width    width of render area
     * @param height   height of render area
     * @param size     render size
     * @param yaw      yaw angle
     * @param options  render options
     */
    public static void renderForExport(MoleculeStructure molecule, int x, int y, int width, int height,
                                       int size, float yaw,
                                       io.github.wurtzitane.avogadro.api.client.MoleculeRenderOptions options) {
        MoleculeStyle style = new MoleculeStyle(options.atomScale(), options.bondScale());
        size = MoleculeViewport.fitSize(molecule, size, width, height, options.cubic(), style);
        renderStyled(molecule, x, y, width, height, size, yaw, options, true);
    }

    private static void renderStyled(MoleculeStructure molecule, int x, int y, int width, int height,
                                     int size, float yaw,
                                     io.github.wurtzitane.avogadro.api.client.MoleculeRenderOptions options,
                                     boolean immediate) {
        beginState();
        try {
            if (!GuiClip.intersect(x, y, x + width, y + height)) return;
            if (options.background() != null) {
                int color = options.background();
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                GL11.glDisable(GL11.GL_LIGHTING);
                GL11.glDisable(GL11.GL_CULL_FACE);
                GL11.glDisable(GL11.GL_ALPHA_TEST);
                GL11.glEnable(GL11.GL_BLEND);
                org.lwjgl.opengl.GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                        GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
                GL11.glColor4f((color >> 16 & 255) / 255f, (color >> 8 & 255) / 255f,
                        (color & 255) / 255f, (color >>> 24) / 255f);
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glVertex2i(x, y); GL11.glVertex2i(x, y + height);
                GL11.glVertex2i(x + width, y + height); GL11.glVertex2i(x + width, y);
                GL11.glEnd();
            }
            renderClipped(molecule, x + (width - size) / 2, y + (height - size) / 2, size,
                    yaw, options.pitch(), x, y, x + width, y + height, options.cubic(),
                    options.panX(), options.panY(), options.zoom(), options.palette(), false, null, immediate,
                    new MoleculeStyle(options.atomScale(), options.bondScale()), options.roll());
        } finally { endState(); }
    }

    /** Render-thread scope shared by a capture batch and its nested molecule draws. */
    public static StateScope exportScope() {
        return new StateScope();
    }

    private static void beginState() {
        if (stateDepth == 0) GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        stateDepth++;
    }

    private static void endState() {
        if (--stateDepth == 0) GL11.glPopAttrib();
    }

    /**
     * Auto-closeable scope managing OpenGL attribute and state preservation across export batches.
     */
    public static final class StateScope implements AutoCloseable {

        private boolean closed;

        private StateScope() {
            beginState();
            exportDepth++;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                exportDepth--;
                endState();
            }
        }
    }

    /** Called on the render thread after definition/resource changes and disconnect. */
    public static void clearCaches() {
        FrameWorkBudget.reset();
        MoleculeAnimationCache.clear();
        GpuMoleculeCache.clear();
        CrystalOutline.clearCache();
        MoleculeItemRenderer.clearBounds();
        cachesInvalid = false;
    }

    /** Invalidates rendering caches, forcing geometry regeneration on subsequent frames. */
    public static void invalidateCaches() {
        cachesInvalid = true;
        PreparedMoleculeGeometry.clear();
    }

    private static void checkCaches() {
        if (cachesInvalid) clearCaches();
    }

    static void drawTransformed(MoleculeStructure molecule, Matrix4f transform, boolean cubic) {
        checkCaches();
        GpuMoleculeCache.draw(molecule, transform, cubic, ElementPalette.snapshot(), exportDepth > 0);
    }

    static Matrix4f transform(Matrix4f result, MoleculeStructure molecule, int x, int y, int size, float yaw,
                              float pitch) {
        return transform(result, molecule, x, y, size, yaw, pitch, 0);
    }

    static Matrix4f transform(Matrix4f result, MoleculeStructure molecule, int x, int y, int size, float yaw,
                              float pitch, float roll) {
        float radius = molecule.radius();
        float scale = (size * .45f) / (radius > .001f ? radius : 1f);
        return result.translation(x + size / 2f, y + size / 2f, 100f).scale(scale, -scale, scale)
                .rotateZ((float) Math.toRadians(roll)).rotateX((float) Math.toRadians(pitch)).rotateY((float) Math.toRadians(yaw))
                .translate(-molecule.centerX(), -molecule.centerY(), -molecule.centerZ());
    }

    /** Shared full-resolution primitive meshes; six floats per vertex. */
    static float[] atomMesh(boolean cubic) {
        if (!cubic) {
            float[] result = new float[SPHERE_VERTICES.length / 4 * 6];
            for (int i = 0, j = 0; i < SPHERE_VERTICES.length; i += 4, j += 6)
                System.arraycopy(SPHERE_VERTICES, i, result, j, 4);
            return result;
        }
        float[][] faces = {
                { 1, -1, -1, 1, -1, 1, 1, 1, 1, 1, 1, -1, 1, 0, 0 },
                { -1, -1, 1, -1, -1, -1, -1, 1, -1, -1, 1, 1, -1, 0, 0 },
                { -1, 1, -1, 1, 1, -1, 1, 1, 1, -1, 1, 1, 0, 1, 0 },
                { -1, -1, -1, -1, -1, 1, 1, -1, 1, 1, -1, -1, 0, -1, 0 },
                { -1, -1, 1, 1, -1, 1, 1, 1, 1, -1, 1, 1, 0, 0, 1 },
                { 1, -1, -1, -1, -1, -1, -1, 1, -1, 1, 1, -1, 0, 0, -1 } };
        float[] result = new float[36 * 6];
        int offset = 0;
        for (float[] face : faces) for (int v : new int[] { 0, 1, 2, 0, 2, 3 }) {
            result[offset++] = face[v * 3];
            result[offset++] = face[v * 3 + 1];
            result[offset++] = face[v * 3 + 2];
            result[offset++] = brightness(face[12], face[13], face[14]);
            offset += 2;
        }
        return result;
    }

    static float[] bondMesh(boolean cubic) {
        Angle[] angles = cubic ? SQUARE_ANGLES : CYLINDER_ANGLES;
        float[] result = new float[(angles.length - 1) * 6 * 6];
        int offset = 0;
        for (int i = 0; i < angles.length - 1; i++) {
            for (int vertex : new int[] { 0, 1, 2, 0, 2, 3 }) {
                int corner = vertex >= 2 ? i + 1 : i;
                Angle position = angles[corner], normal = cubic ? SQUARE_NORMALS[i] : position;
                result[offset++] = position.cos();
                result[offset++] = position.sin();
                result[offset++] = (vertex == 1 || vertex == 2) ? 1 : 0;
                result[offset++] = normal.cos();
                result[offset++] = normal.sin();
                offset++;
            }
        }
        return result;
    }

    /** CPU geometry submission, kept separate from the OpenGL state scope. */
    static void appendGeometry(BufferBuilder buffer, MoleculeStructure molecule,
                               int x, int y, int size, float angle, boolean cubic) {
        appendGeometry(buffer, molecule, x, y, size, angle, 15f, cubic);
    }

    static void appendGeometry(BufferBuilder buffer, MoleculeStructure molecule,
                               int x, int y, int size, float yaw, float pitch, boolean cubic) {
        appendGeometry(buffer, molecule, x, y, size, yaw, pitch, cubic, ElementPalette.snapshot());
    }

    static void appendGeometry(BufferBuilder buffer, MoleculeStructure molecule,
                               int x, int y, int size, float yaw, float pitch, boolean cubic,
                               ElementPalette.Snapshot palette) {
        var atoms = molecule.atoms();
        var bonds = molecule.bonds();
        if (atoms.isEmpty() || size <= 0) {
            return;
        }
        float radius = molecule.radius();
        float scale = (size * 0.45f) / (radius > 0.001f ? radius : 1f);
        Matrix4f transform = new Matrix4f().translation(x + size / 2f, y + size / 2f, 100f)
                .scale(scale, -scale, scale)
                .rotateX((float) Math.toRadians(pitch))
                .rotateY((float) Math.toRadians(yaw))
                .translate(-molecule.centerX(), -molecule.centerY(), -molecule.centerZ());
        appendTransformedGeometry(buffer, molecule, transform, cubic, palette);
    }

    static void appendTransformedGeometry(BufferBuilder buffer, MoleculeStructure molecule, Matrix4f transform,
                                          boolean cubic, ElementPalette.Snapshot palette) {
        appendTransformedGeometry(buffer, molecule, transform, cubic, palette, MoleculeStyle.DEFAULT);
    }

    static void appendTransformedGeometry(BufferBuilder buffer, MoleculeStructure molecule, Matrix4f transform,
                                          boolean cubic, ElementPalette.Snapshot palette, MoleculeStyle style) {
        var atoms = molecule.atoms();
        var bonds = molecule.bonds();
        var elements = new ArrayList<DrawElement>(atoms.size() + bonds.size());
        for (Atom atom : atoms) {
            GeometryPreparation.checkCancelled();
            elements.add(new DrawElement(viewZ(atom, transform), atom, null, 0));
        }
        for (MoleculeStructure.Bond bond : bonds) {
            GeometryPreparation.checkCancelled();
            Atom from = atoms.get(bond.from());
            Atom to = atoms.get(bond.to());
            elements.add(new DrawElement(viewZMid(from, to, transform), from, to, bond.type().order()));
        }
        // List.sort is stable: ties retain the original atom-then-bond insertion order.
        elements.sort(Comparator.comparingDouble(DrawElement::viewZ));
        for (DrawElement element : elements) {
            GeometryPreparation.checkCancelled();
            if (element.to() != null) {
                bond(buffer, transform, element.from(), element.to(), element.order(), cubic, style);
            } else {
                drawAtom(buffer, transform, element.from(), cubic, palette, style);
            }
        }
    }

    private static float viewZMid(Atom a, Atom b, Matrix4f m) {
        float mx = (a.x() + b.x()) * 0.5f;
        float my = (a.y() + b.y()) * 0.5f;
        float mz = (a.z() + b.z()) * 0.5f;
        return m.m02() * mx + m.m12() * my + m.m22() * mz + m.m32();
    }

    private static float viewZ(Atom p, Matrix4f m) {
        return m.m02() * p.x() + m.m12() * p.y() + m.m22() * p.z() + m.m32();
    }

    private static void drawAtom(BufferBuilder buffer, Matrix4f transform, Atom atom, boolean cubic,
                                 ElementPalette.Snapshot palette, MoleculeStyle style) {
        int color = palette.color(atom.element().symbol(), atom.element().color());
        float radius = atom.element().radius() * SPHERE_SCALE * style.atoms();
        float red = ((color >> 16) & 0xFF) / 255f;
        float green = ((color >> 8) & 0xFF) / 255f;
        float blue = (color & 0xFF) / 255f;
        if (cubic) {
            cube(buffer, transform, atom.x(), atom.y(), atom.z(), radius, red, green, blue);
        } else {
            sphere(buffer, transform, atom.x(), atom.y(), atom.z(), radius, red, green, blue);
        }
    }

    private static float[] perpDir(float ax, float ay, float az) {
        float axa = Math.abs(ax), aya = Math.abs(ay), aza = Math.abs(az);
        if (axa <= aya && axa <= aza) {
            float il = 1f / (float) Math.sqrt(az * az + ay * ay);
            return new float[] { 0f, az * il, -ay * il };
        } else if (aya <= aza) {
            float il = 1f / (float) Math.sqrt(az * az + ax * ax);
            return new float[] { az * il, 0f, -ax * il };
        } else {
            float il = 1f / (float) Math.sqrt(ay * ay + ax * ax);
            return new float[] { -ay * il, ax * il, 0f };
        }
    }

    private static void sphere(BufferBuilder buf, Matrix4f mat,
                               float cx, float cy, float cz,
                               float radius, float r, float g, float b) {
        for (int i = 0; i < SPHERE_VERTICES.length; i += 4) {
            emitLit(buf, mat,
                    cx + SPHERE_VERTICES[i] * radius,
                    cy + SPHERE_VERTICES[i + 1] * radius,
                    cz + SPHERE_VERTICES[i + 2] * radius,
                    r, g, b, SPHERE_VERTICES[i + 3]);
        }
    }

    private static float[] sphereVertices() {
        // Preserve duplicated triangle vertices, including the original seam/pole rounding.
        float[] vertices = new float[SPHERE_STACKS * SPHERE_SLICES * 6 * 4];
        int offset = 0;
        for (int stack = 0; stack < SPHERE_STACKS; stack++) {
            Angle latitude1 = SPHERE_LATITUDES[stack];
            Angle latitude2 = SPHERE_LATITUDES[stack + 1];
            float sP1 = latitude1.sin(), cP1 = latitude1.cos();
            float sP2 = latitude2.sin(), cP2 = latitude2.cos();

            for (int slice = 0; slice < SPHERE_SLICES; slice++) {
                Angle longitude1 = SPHERE_LONGITUDES[slice];
                Angle longitude2 = SPHERE_LONGITUDES[slice + 1];
                float sT1 = longitude1.sin(), cT1 = longitude1.cos();
                float sT2 = longitude2.sin(), cT2 = longitude2.cos();

                float n1x = sP1 * cT1, n1y = cP1, n1z = sP1 * sT1;
                float n2x = sP2 * cT1, n2y = cP2, n2z = sP2 * sT1;
                float n3x = sP2 * cT2, n3y = cP2, n3z = sP2 * sT2;
                float n4x = sP1 * cT2, n4y = cP1, n4z = sP1 * sT2;

                offset = sphereVertex(vertices, offset, n1x, n1y, n1z);
                offset = sphereVertex(vertices, offset, n2x, n2y, n2z);
                offset = sphereVertex(vertices, offset, n3x, n3y, n3z);
                offset = sphereVertex(vertices, offset, n1x, n1y, n1z);
                offset = sphereVertex(vertices, offset, n3x, n3y, n3z);
                offset = sphereVertex(vertices, offset, n4x, n4y, n4z);
            }
        }
        return vertices;
    }

    private static int sphereVertex(float[] vertices, int offset, float nx, float ny, float nz) {
        vertices[offset] = nx;
        vertices[offset + 1] = ny;
        vertices[offset + 2] = nz;
        vertices[offset + 3] = brightness(nx, ny, nz);
        return offset + 4;
    }

    private static void quad(BufferBuilder buf, Matrix4f mat,
                             float ax, float ay, float az,
                             float bx, float by, float bz,
                             float cx, float cy, float cz,
                             float dx, float dy, float dz,
                             float nx, float ny, float nz,
                             float r, float g, float b) {
        float light = brightness(nx, ny, nz);
        emitLit(buf, mat, ax, ay, az, r, g, b, light);
        emitLit(buf, mat, bx, by, bz, r, g, b, light);
        emitLit(buf, mat, cx, cy, cz, r, g, b, light);
        emitLit(buf, mat, ax, ay, az, r, g, b, light);
        emitLit(buf, mat, cx, cy, cz, r, g, b, light);
        emitLit(buf, mat, dx, dy, dz, r, g, b, light);
    }

    private static void cube(BufferBuilder buf, Matrix4f mat,
                             float cx, float cy, float cz,
                             float radius, float r, float g, float b) {
        float x0 = cx - radius, x1 = cx + radius;
        float y0 = cy - radius, y1 = cy + radius;
        float z0 = cz - radius, z1 = cz + radius;
        quad(buf, mat, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, 1f, 0f, 0f, r, g, b);
        quad(buf, mat, x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1, -1f, 0f, 0f, r, g, b);
        quad(buf, mat, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, 0f, 1f, 0f, r, g, b);
        quad(buf, mat, x0, y0, z0, x0, y0, z1, x1, y0, z1, x1, y0, z0, 0f, -1f, 0f, r, g, b);
        quad(buf, mat, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, 0f, 0f, 1f, r, g, b);
        quad(buf, mat, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, 0f, 0f, -1f, r, g, b);
    }

    private static void bond(BufferBuilder buf, Matrix4f mat, Atom from, Atom to, int order, boolean cubic, MoleculeStyle style) {
        if (order <= 1) {
            cylinder(buf, mat, from, to, (SINGLE_BOND_RADIUS * style.bonds()), cubic);
            return;
        }
        float dx = to.x() - from.x();
        float dy = to.y() - from.y();
        float dz = to.z() - from.z();
        if (dx * dx + dy * dy + dz * dz < 0.00000001f) {
            return;
        }
        float[] perp = perpDir(dx, dy, dz);
        float px = perp[0];
        float py = perp[1];
        float pz = perp[2];
        if (order == 2) {
            cylinderOffset(buf, mat, from, to, px * (MULTI_BOND_GAP * style.bonds()), py * (MULTI_BOND_GAP * style.bonds()), pz * (MULTI_BOND_GAP * style.bonds()),
                    (MULTI_BOND_RADIUS * style.bonds()), cubic);
            cylinderOffset(buf, mat, from, to, -px * (MULTI_BOND_GAP * style.bonds()), -py * (MULTI_BOND_GAP * style.bonds()), -pz * (MULTI_BOND_GAP * style.bonds()),
                    (MULTI_BOND_RADIUS * style.bonds()), cubic);
        } else {
            cylinder(buf, mat, from, to, (MULTI_BOND_RADIUS * style.bonds()), cubic);
            cylinderOffset(buf, mat, from, to, px * (MULTI_BOND_GAP * style.bonds()) * 2f, py * (MULTI_BOND_GAP * style.bonds()) * 2f,
                    pz * (MULTI_BOND_GAP * style.bonds()) * 2f, (MULTI_BOND_RADIUS * style.bonds()), cubic);
            cylinderOffset(buf, mat, from, to, -px * (MULTI_BOND_GAP * style.bonds()) * 2f, -py * (MULTI_BOND_GAP * style.bonds()) * 2f,
                    -pz * (MULTI_BOND_GAP * style.bonds()) * 2f, (MULTI_BOND_RADIUS * style.bonds()), cubic);
        }
    }

    private static void cylinderOffset(BufferBuilder buf, Matrix4f mat, Atom from, Atom to,
                                       float ox, float oy, float oz, float radius, boolean cubic) {
        cylinderLine(buf, mat,
                from.x() + ox, from.y() + oy, from.z() + oz,
                to.x() + ox, to.y() + oy, to.z() + oz,
                radius, cubic);
    }

    private static void cylinder(BufferBuilder buf, Matrix4f mat, Atom from, Atom to, float radius, boolean cubic) {
        cylinderLine(buf, mat, from.x(), from.y(), from.z(), to.x(), to.y(), to.z(), radius, cubic);
    }

    private static void cylinderLine(BufferBuilder buf, Matrix4f mat,
                                     float fx, float fy, float fz,
                                     float tx, float ty, float tz, float radius, boolean square) {
        float dx = tx - fx, dy = ty - fy, dz = tz - fz;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 0.0001f) {
            return;
        }

        float nx = dx / len, ny = dy / len, nz = dz / len;

        float ax = Math.abs(nx), ay = Math.abs(ny), az = Math.abs(nz);
        float refx, refy, refz;
        if (ax <= ay && ax <= az) {
            refx = 1f;
            refy = 0f;
            refz = 0f;
        } else if (ay <= az) {
            refx = 0f;
            refy = 1f;
            refz = 0f;
        } else {
            refx = 0f;
            refy = 0f;
            refz = 1f;
        }

        float ux = ny * refz - nz * refy;
        float uy = nz * refx - nx * refz;
        float uz = nx * refy - ny * refx;
        float ul = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
        ux /= ul;
        uy /= ul;
        uz /= ul;

        float rx = ny * uz - nz * uy;
        float ry = nz * ux - nx * uz;
        float rz = nx * uy - ny * ux;

        Angle[] angles = square ? SQUARE_ANGLES : CYLINDER_ANGLES;
        for (int i = 0; i < angles.length - 1; i++) {
            float c1 = angles[i].cos(), s1 = angles[i].sin();
            float c2 = angles[i + 1].cos(), s2 = angles[i + 1].sin();

            float nn1x = rx * c1 + ux * s1, nn1y = ry * c1 + uy * s1, nn1z = rz * c1 + uz * s1;
            float nn1l = (float) Math.sqrt(nn1x * nn1x + nn1y * nn1y + nn1z * nn1z);
            nn1x /= nn1l;
            nn1y /= nn1l;
            nn1z /= nn1l;

            float nn2x = rx * c2 + ux * s2, nn2y = ry * c2 + uy * s2, nn2z = rz * c2 + uz * s2;
            float nn2l = (float) Math.sqrt(nn2x * nn2x + nn2y * nn2y + nn2z * nn2z);
            nn2x /= nn2l;
            nn2y /= nn2l;
            nn2z /= nn2l;

            float p1ax = fx + (rx * c1 + ux * s1) * radius;
            float p1ay = fy + (ry * c1 + uy * s1) * radius;
            float p1az = fz + (rz * c1 + uz * s1) * radius;
            float p1bx = fx + (rx * c2 + ux * s2) * radius;
            float p1by = fy + (ry * c2 + uy * s2) * radius;
            float p1bz = fz + (rz * c2 + uz * s2) * radius;
            float p2ax = tx + (rx * c1 + ux * s1) * radius;
            float p2ay = ty + (ry * c1 + uy * s1) * radius;
            float p2az = tz + (rz * c1 + uz * s1) * radius;
            float p2bx = tx + (rx * c2 + ux * s2) * radius;
            float p2by = ty + (ry * c2 + uy * s2) * radius;
            float p2bz = tz + (rz * c2 + uz * s2) * radius;

            float f1x = nn1x, f1y = nn1y, f1z = nn1z;
            float f2x = nn2x, f2y = nn2y, f2z = nn2z;
            if (square) {
                float cm = SQUARE_NORMALS[i].cos(), sm = SQUARE_NORMALS[i].sin();
                float qx = rx * cm + ux * sm, qy = ry * cm + uy * sm, qz = rz * cm + uz * sm;
                float ql = (float) Math.sqrt(qx * qx + qy * qy + qz * qz);
                f1x = f2x = qx / ql;
                f1y = f2y = qy / ql;
                f1z = f2z = qz / ql;
            }

            emit(buf, mat, p1ax, p1ay, p1az, f1x, f1y, f1z, BOND_R, BOND_G, BOND_B);
            emit(buf, mat, p2ax, p2ay, p2az, f1x, f1y, f1z, BOND_R, BOND_G, BOND_B);
            emit(buf, mat, p2bx, p2by, p2bz, f2x, f2y, f2z, BOND_R, BOND_G, BOND_B);

            emit(buf, mat, p1ax, p1ay, p1az, f1x, f1y, f1z, BOND_R, BOND_G, BOND_B);
            emit(buf, mat, p2bx, p2by, p2bz, f2x, f2y, f2z, BOND_R, BOND_G, BOND_B);
            emit(buf, mat, p1bx, p1by, p1bz, f2x, f2y, f2z, BOND_R, BOND_G, BOND_B);
        }
    }

    private static void emit(BufferBuilder buf, Matrix4f mat,
                             float px, float py, float pz,
                             float nx, float ny, float nz,
                             float r, float g, float b) {
        emitLit(buf, mat, px, py, pz, r, g, b, brightness(nx, ny, nz));
    }

    private static float brightness(float nx, float ny, float nz) {
        float diffuse = Math.max(0f, nx * LIGHT_DIR.x() + ny * LIGHT_DIR.y() + nz * LIGHT_DIR.z());
        return AMBIENT + (1f - AMBIENT) * diffuse;
    }

    private static void emitLit(BufferBuilder buf, Matrix4f mat, float px, float py, float pz,
                                float r, float g, float b, float brightness) {
        float x = mat.m00() * px + mat.m10() * py + mat.m20() * pz + mat.m30();
        float y = mat.m01() * px + mat.m11() * py + mat.m21() * pz + mat.m31();
        float z = mat.m02() * px + mat.m12() * py + mat.m22() * pz + mat.m32();

        buf.pos(x, y, z).color(r * brightness, g * brightness, b * brightness, 1f).endVertex();
    }

    /**
     * Record representing a drawing element sorted by view Z-depth.
     */
    private record DrawElement(float viewZ, Atom from, Atom to, int order) {}

    private static Angle[] arc(int segments, double radians) {
        Angle[] angles = new Angle[segments + 1];
        for (int i = 0; i <= segments; i++) {
            angles[i] = Angle.at((float) (radians * i / segments));
        }
        return angles;
    }

    private static Angle[] squareNormals() {
        Angle[] normals = new Angle[4];
        for (int i = 0; i < normals.length; i++) {
            normals[i] = Angle.at((SQUARE_ANGLES[i].radians() + SQUARE_ANGLES[i + 1].radians()) * 0.5f);
        }
        return normals;
    }

    /**
     * Record representing precalculated trigonometric angle values.
     */
    private record Angle(float radians, float sin, float cos) {

        static Angle at(float radians) {
            return new Angle(radians, (float) Math.sin(radians), (float) Math.cos(radians));
        }
    }
}
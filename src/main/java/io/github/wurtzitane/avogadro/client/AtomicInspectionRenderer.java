package io.github.wurtzitane.avogadro.client;

import io.github.wurtzitane.avogadro.api.AtomicConfiguration;
import io.github.wurtzitane.avogadro.Config;
import io.github.wurtzitane.avogadro.molecule.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import java.nio.FloatBuffer;
import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CancellationException;

/**
 * Detailed examiner renderer.
 * <p>Shared sphere mesh, streamed instance transforms, no world renderer dependency.
 */
public final class AtomicInspectionRenderer implements AutoCloseable {

    /** Stride size for particle data buffers. */
    private static final int STRIDE = 8;

    /** Molecule definition being rendered. */
    private final MoleculeDef definition;

    /** Optional fixed element palette snapshot. */
    private final ElementPalette.Snapshot fixedPalette;

    /** Fixed visibility array. */
    private final boolean[] fixedVisibility;

    /** List of nuclear geometries per atom. */
    private final List<List<NuclearGeometry.Nucleon>> nuclei;

    /** Shared nuclear packing cache map. */
    private final Map<AtomicConfiguration, NuclearGeometry.Packing> sharedNuclei = new HashMap<>();

    /** Array of nucleus radii. */
    private final float[] nucleusRadii;

    /** Shared electron cloud cache map. */
    private final Map<AtomicConfiguration, ElectronCloud> sharedElectrons = new HashMap<>();

    /** Inspection model data. */
    private final InspectionModelData model;

    /** Atom detail levels array. */
    private final byte[] detail;

    /** Depth order comparator for sorting render primitives. */
    private final java.util.Comparator<Integer> depthOrder;

    /** Float buffers for particles, orbit lines, and envelopes. */
    private FloatBuffer nucleusParticles = BufferUtils.createFloatBuffer(8);
    private FloatBuffer orbitLines = BufferUtils.createFloatBuffer(3);
    private FloatBuffer envelopes = BufferUtils.createFloatBuffer(8);
    private FloatBuffer fallbackVertices;

    /** OpenGL buffer and array object identifiers. */
    private int nucleusInstances, orbitBuffer, orbitVao, envelopeShape, envelopeInstances, fallbackBuffer, fallbackVao;

    /** Flags tracking detail and envelope dirty states. */
    private boolean detailDirty = true;
    private boolean envelopesDirty = true;

    /** Timestamp for electron animation tracking. */
    private long electronTime = Long.MIN_VALUE;

    /** Cached envelope rotation angles and focus target. */
    private float envelopeYaw = Float.NaN, envelopePitch = Float.NaN;
    private int envelopeFocus = Integer.MIN_VALUE;

    /** Pre-calculated particle and envelope meshes. */
    private static final float[] PARTICLE_MESH = particleMesh();
    private static final float[] ENVELOPE_MESH = MoleculeRenderer.atomMesh(false);

    /** Bond structure representation. */
    private final MoleculeStructure bonds;

    /** Transformation matrix and buffer representations. */
    private final Matrix4f transform = new Matrix4f();
    private final FloatBuffer matrix = BufferUtils.createFloatBuffer(16);
    private FloatBuffer particles = BufferUtils.createFloatBuffer(1024);
    private int shape, instances, program, vao;

    /** Status flags and visibility tracking. */
    private boolean attempted;
    private boolean rendered;
    private boolean[] lastVisibility;
    private long paletteRevision = Long.MIN_VALUE;
    private int[] colors;
    private final float[] depth;
    private final float[] mesh = PARTICLE_MESH;
    private final ArrayList<Integer> order = new ArrayList<>();
    private final Vector3f projected = new Vector3f();
    private int focused = -1;
    private float drawScale;
    private final float[] reveal;
    private boolean moleculeReveal;
    private boolean overview;
    private long revealTime;
    private float revealZoom = 1;

    /**
     * Calculates the reveal target ratio based on zoom level.
     * <p>Fade over one zoom unit after the configured threshold; viewport size does not affect it.
     *
     * @param zoom      current zoom level
     * @param startZoom starting reveal threshold zoom
     * @return interpolated reveal value between 0 and 1
     */
    static float revealTarget(float zoom, float startZoom) {
        float t = Math.max(0, Math.min(1, zoom - startZoom));
        return t * t * (3 - 2 * t);
    }

    /** Updates the reveal transition state. */
    private boolean updateReveal(long now) {
        float step = revealTime == 0 ? 0 : Math.min(1, Math.max(0, (now - revealTime) / 180_000_000f));
        revealTime = now;
        boolean any = false;
        for (int i = 0; i < reveal.length; i++) {
            float target = moleculeReveal ? revealTarget(revealZoom, Config.ATOM_REVEAL_ZOOM.get().floatValue()) : 1;
            float next = reveal[i] + Math.clamp(target - reveal[i], -step, step);
            if (!moleculeReveal) {
                next = 1;
            } else if (revealZoom <= Config.ATOM_REVEAL_ZOOM.get()) {
                next = 0;
            }
            if (next != reveal[i]) {
                envelopesDirty = true;
            }
            reveal[i] = next;
            any |= next > 0;
        }
        return any;
    }

    /**
     * Constructs a new {@link AtomicInspectionRenderer} for a molecule definition.
     *
     * @param definition molecule definition
     */
    public AtomicInspectionRenderer(MoleculeDef definition) {
        this(definition, null);
    }

    /**
     * Constructs a new {@link AtomicInspectionRenderer} with an optional element palette snapshot.
     * <p>Export jobs retain their palette even if the user changes appearance mid-export.
     *
     * @param definition molecule definition
     * @param palette    element palette snapshot
     */
    public AtomicInspectionRenderer(MoleculeDef definition, ElementPalette.Snapshot palette) {
        fixedPalette = palette;
        fixedVisibility = palette == null ? null : visibility();
        this.definition = definition;
        depth = new float[definition.structure().atoms().size()];
        depthOrder = java.util.Comparator.comparingDouble(i -> depth[i]);
        model = InspectionModelData.get(definition);
        nucleusRadii = new float[depth.length];
        detail = new byte[depth.length];
        reveal = new float[depth.length];
        Arrays.fill(detail, (byte) -2);
        nuclei = new ArrayList<>(definition.structure().atoms().size());
        for (int i = 0; i < definition.structure().atoms().size(); i++) {
            GeometryPreparation.checkCancelled();
            nuclei.add(null);
            order.add(i);
        }
        bonds = model.bonds();
    }

    /** Checks whether the GPU cache and inspection components are fully ready. */
    boolean isReady() {
        ElementPalette.Snapshot palette = fixedPalette == null ? ElementPalette.snapshot() : fixedPalette;
        return rendered && (overview ? GpuMoleculeCache.isReady(definition.structure(), Config.CUBIC_ATOMS.get(), palette)
                : focused >= 0 || bonds.bonds().isEmpty() || GpuMoleculeCache.isReady(bonds, false, palette));
    }

    /** Renders the atomic inspection view. */
    public void render(int width, int height, MoleculeCamera camera, int focusedAtom) {
        render(width, height, camera, focusedAtom, System.nanoTime(), MoleculeRenderer.rotationAngle());
    }

    /** Renders the atomic inspection view with a specified animation timestamp. */
    public void render(int width, int height, MoleculeCamera camera, int focusedAtom, long animationNanos) {
        render(width, height, camera, focusedAtom, animationNanos,
                MoleculeRenderer.rotationAngle(animationNanos / 1_000_000_000.0));
    }

    /** Renders the atomic inspection view with full animation parameters. */
    public void render(int width, int height, MoleculeCamera camera, int focusedAtom,
                       long animationNanos, float automaticYaw) {
        if (width <= 0 || height <= 0) {
            return;
        }
        focused = focusedAtom;
        var structure = definition.structure();
        float radius = viewRadius(focused);
        drawScale = Math.min(width, height) * .42f / Math.max(.1f, radius) * camera.zoom();
        float centerX = focused < 0 ? structure.centerX() : structure.atoms().get(focused).x();
        float centerY = focused < 0 ? structure.centerY() : structure.atoms().get(focused).y();
        float centerZ = focused < 0 ? structure.centerZ() : structure.atoms().get(focused).z();
        float pitch = camera.pitch(animationNanos);
        float yaw = camera.yaw(automaticYaw, animationNanos);
        transform.translation(width / 2f + camera.panX(), height / 2f + camera.panY(), 100)
                .scale(drawScale, -drawScale, drawScale / Math.max(1, camera.zoom()))
                .rotateX((float) Math.toRadians(pitch))
                .rotateY((float) Math.toRadians(yaw))
                .translate(-centerX, -centerY, -centerZ);
        moleculeReveal = focused < 0 && structure.atoms().size() > 1;
        revealZoom = camera.zoom();
        boolean showParts = updateReveal(System.nanoTime());
        overview = moleculeReveal && !showParts;
        var palette = fixedPalette == null ? ElementPalette.snapshot() : fixedPalette;
        if (colors == null || paletteRevision != palette.revision()) {
            colors = new int[structure.atoms().size()];
            for (int i = 0; i < colors.length; i++) {
                colors[i] = palette.color(structure.atoms().get(i).element().symbol());
            }
            paletteRevision = palette.revision();
            Arrays.fill(detail, (byte) -2);
            envelopesDirty = true;
        }
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int previousVao = GL.getCapabilities().OpenGL30 ? GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING) : 0;
        int previousMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        try {
            if (!GuiClip.intersect(0, 0, width, height)) {
                return;
            }
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glShadeModel(GL11.GL_SMOOTH);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glDepthMask(true);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glClearDepth(1);
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
            if (moleculeReveal && !showParts) {
                MoleculeRenderer.drawTransformed(structure, transform, Config.CUBIC_ATOMS.get());
                // Clear previously prepared atomic detail when returning to the overview.
                updateDetail();
                GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
                rendered = true;
                return;
            }
            if (focused < 0 && !structure.bonds().isEmpty()) {
                // Reserve cloud silhouettes before drawing bonds. No background color or stencil attachment is needed.
                GL11.glColorMask(false, false, false, false);
                GL11.glDepthFunc(GL11.GL_ALWAYS);
                GL11.glDepthRange(0, 0);
                try {
                    MoleculeRenderer.drawTransformed(model.cloudMask(), transform, false);
                } finally {
                    GL11.glColorMask(true, true, true, true);
                    GL11.glDepthRange(0, 1);
                    GL11.glDepthFunc(GL11.GL_LEQUAL);
                }
                if (GpuMoleculeCache.isReady(model.cloudMask(), false, palette)) {
                    MoleculeRenderer.drawTransformed(bonds, transform, false);
                }
            }
            // Keep atomic components in front of bond cylinders while retaining atom-to-atom depth.
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
            matrix.clear();
            transform.get(matrix);
            GL11.glMultMatrixf(matrix);
            ensureGpu();
            updateDetail();
            boolean[] visible = fixedVisibility == null ? visibility() : fixedVisibility;
            if (!Arrays.equals(lastVisibility, visible)) {
                detailDirty = true;
                envelopesDirty = true;
                lastVisibility = visible.clone();
            }
            if (visible[0]) {
                drawParticles(nucleusParticles, nucleusInstances, detailDirty, false);
            }
            if (visible[1]) {
                drawOrbits();
            }
            boolean electronsDirty = detailDirty || electronTime != animationNanos;
            if (electronsDirty) {
                particles.clear();
                for (int i = 0; i < structure.atoms().size(); i++) {
                    if (detail[i] < 1) {
                        continue;
                    }
                    var atom = structure.atoms().get(i);
                    var configuration = model.configuration(i);
                    if (configuration == null) {
                        continue;
                    }
                    float r = atom.element().radius();
                    ElectronCloud cloud = sharedElectrons.computeIfAbsent(configuration, ElectronCloud::new);
                    cloud.update(animationNanos);
                    for (int electron = 0; electron < cloud.positions.length; electron += 3) {
                        particle(atom.x() + cloud.positions[electron] * r, atom.y() + cloud.positions[electron + 1] * r,
                                atom.z() + cloud.positions[electron + 2] * r, r * .032f, 0xFFE480, 1);
                    }
                }
                particles.flip();
                electronTime = animationNanos;
            }
            if (visible[2]) {
                drawParticles(particles, instances, electronsDirty, false);
            }
            detailDirty = false;
            // Transparent envelopes must follow opaque nuclei/electrons and be sorted back to front.
            GL11.glDepthMask(false);
            GL11.glEnable(GL11.GL_CULL_FACE);
            GL11.glCullFace(GL11.GL_BACK);
            boolean reorder = envelopesDirty || envelopeYaw != yaw || envelopePitch != pitch || envelopeFocus != focused;
            if (reorder) {
                for (int i = 0; i < depth.length; i++) {
                    var atom = structure.atoms().get(i);
                    depth[i] = transform.transformPosition(projected.set(atom.x(), atom.y(), atom.z())).z;
                }
                order.sort(depthOrder);
                FloatBuffer electrons = particles;
                particles = envelopes;
                particles.clear();
                for (int i : order) {
                    if (focused >= 0 && i != focused) {
                        continue;
                    }
                    var atom = structure.atoms().get(i);
                    // Both clouds follow the opaque particles. Keep the inner cloud
                    // separate so hiding electron clouds still leaves a cohesive nucleus.
                    if (visible[0] && (!moleculeReveal || reveal[i] > 0)) {
                        particle(atom.x(), atom.y(), atom.z(), nucleusRadii[i] * 1.07f, 0x8F9EBC, .12f);
                    }
                    if (moleculeReveal) {
                        if (reveal[i] < 1) {
                            particle(atom.x(), atom.y(), atom.z(), atom.element().radius(), colors[i], 1 - reveal[i]);
                        }
                    } else if (visible[3] && (model.configuration(i) == null || model.configuration(i).electrons() > 0)) {
                        particle(atom.x(), atom.y(), atom.z(), atom.element().radius() * .96f, colors[i], .09f);
                    }
                }
                particles.flip();
                envelopes = particles;
                particles = electrons;
                envelopeYaw = yaw;
                envelopePitch = pitch;
                envelopeFocus = focused;
                envelopesDirty = false;
            }
            drawParticles(envelopes, envelopeInstances, reorder, true);
            GL11.glDepthMask(true);
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
            rendered = true;
        } finally {
            GL11.glPopMatrix();
            GL11.glMatrixMode(previousMode);
            if (GL.getCapabilities().OpenGL30) {
                GL30.glBindVertexArray(previousVao);
            }
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousBuffer);
            GL20.glUseProgram(previousProgram);
            GL11.glPopAttrib();
        }
    }

    /** Retrieves the current visibility configuration array. */
    private static boolean[] visibility() {
        return new boolean[]{Config.ATOM_NUCLEUS.get(), Config.ATOM_ORBITS.get(), Config.ATOM_ELECTRONS.get(), Config.ATOM_CLOUD.get()};
    }

    /**
     * Initializes an export cursor for rendering frozen frames during media export.
     * <p>An isolated export renderer holds one frozen frame while bounded draw ranges finish.
     *
     * @param width           viewport width
     * @param height          viewport height
     * @param camera          molecule camera
     * @param focusedAtom     focused atom index
     * @param animationNanos  animation time in nanoseconds
     * @param automaticYaw    automatic yaw angle
     * @return a new {@link ExportCursor} instance
     */
    public ExportCursor beginExport(int width, int height, MoleculeCamera camera, int focusedAtom,
                                    long animationNanos, float automaticYaw) {
        return beginExport(width, height, camera, focusedAtom, animationNanos, automaticYaw, 0);
    }

    /** Initializes an export cursor with animation loop settings. */
    public ExportCursor beginExport(int width, int height, MoleculeCamera camera, int focusedAtom,
                                    long animationNanos, float automaticYaw, double loopSeconds) {
        if (!Double.isFinite(loopSeconds) || loopSeconds < 0) {
            throw new IllegalArgumentException("Invalid animation duration");
        }
        return new ExportCursor(width, height, camera, focusedAtom, animationNanos, automaticYaw, loopSeconds);
    }

    /**
     * Cursor for managing incremental rendering passes during atom media exports.
     */
    public final class ExportCursor implements AutoCloseable {
        private final int width, height;
        private final long animationNanos;
        private final float yaw, pitch;
        private final double loopSeconds;
        private final boolean[] visible;
        private final ElementPalette.Snapshot exportPalette;
        private Future<Boolean> preparation;
        private GpuMoleculeCache.ExportCursor links;
        private int phase, first;
        private boolean depthStarted, closed;

        /** Constructs a new {@link ExportCursor}. */
        private ExportCursor(int width, int height, MoleculeCamera camera, int focusedAtom,
                             long animationNanos, float automaticYaw, double loopSeconds) {
            this.width = width;
            this.height = height;
            this.animationNanos = animationNanos;
            this.loopSeconds = loopSeconds;
            focused = focusedAtom;
            var structure = definition.structure();
            float radius = viewRadius(focused);
            drawScale = Math.min(width, height) * .42f / Math.max(.1f, radius) * camera.zoom();
            float centerX = focused < 0 ? structure.centerX() : structure.atoms().get(focused).x();
            float centerY = focused < 0 ? structure.centerY() : structure.atoms().get(focused).y();
            float centerZ = focused < 0 ? structure.centerZ() : structure.atoms().get(focused).z();
            pitch = camera.pitch(animationNanos);
            yaw = camera.yaw(automaticYaw, animationNanos);
            transform.translation(width / 2f + camera.panX(), height / 2f + camera.panY(), 100)
                    .scale(drawScale, -drawScale, drawScale)
                    .rotateX((float) Math.toRadians(pitch)).rotateY((float) Math.toRadians(yaw))
                    .translate(-centerX, -centerY, -centerZ);
            visible = (fixedVisibility == null ? visibility() : fixedVisibility).clone();
            exportPalette = fixedPalette == null ? ElementPalette.snapshot() : fixedPalette;
        }

        /** Prepares frame data asynchronously. CPU-only preparation belongs to the bounded worker pool, never a render callback. */
        private boolean prepareFrame() {
            GeometryPreparation.checkCancelled();
            var structure = definition.structure();
            var palette = exportPalette;
            if (colors == null || paletteRevision != palette.revision()) {
                colors = new int[structure.atoms().size()];
                for (int i = 0; i < colors.length; i++) {
                    GeometryPreparation.checkCancelled();
                    colors[i] = palette.color(structure.atoms().get(i).element().symbol());
                }
                paletteRevision = palette.revision();
                Arrays.fill(detail, (byte) -2);
                envelopesDirty = true;
            }
            updateDetail();
            if (!Arrays.equals(lastVisibility, visible)) {
                detailDirty = true;
                envelopesDirty = true;
                lastVisibility = visible.clone();
            }
            if (detailDirty || electronTime != animationNanos) {
                particles.clear();
                for (int i = 0; i < structure.atoms().size(); i++) {
                    GeometryPreparation.checkCancelled();
                    if (detail[i] < 1) {
                        continue;
                    }
                    var atom = structure.atoms().get(i);
                    var configuration = model.configuration(i);
                    if (configuration == null) {
                        continue;
                    }
                    float r = atom.element().radius();
                    ElectronCloud cloud = sharedElectrons.computeIfAbsent(configuration, ElectronCloud::new);
                    cloud.update(animationNanos, loopSeconds);
                    for (int electron = 0; electron < cloud.positions.length; electron += 3) {
                        particle(atom.x() + cloud.positions[electron] * r, atom.y() + cloud.positions[electron + 1] * r,
                                atom.z() + cloud.positions[electron + 2] * r, r * .032f, 0xFFE480, 1);
                    }
                }
                particles.flip();
                electronTime = animationNanos;
            }
            boolean reorder = envelopesDirty || envelopeYaw != yaw || envelopePitch != pitch || envelopeFocus != focused;
            if (reorder) {
                for (int i = 0; i < depth.length; i++) {
                    GeometryPreparation.checkCancelled();
                    var atom = structure.atoms().get(i);
                    depth[i] = transform.transformPosition(projected.set(atom.x(), atom.y(), atom.z())).z;
                }
                order.sort(depthOrder);
                FloatBuffer electrons = particles;
                particles = envelopes;
                particles.clear();
                for (int i : order) {
                    GeometryPreparation.checkCancelled();
                    if (focused >= 0 && i != focused) {
                        continue;
                    }
                    var atom = structure.atoms().get(i);
                    if (visible[0]) {
                        particle(atom.x(), atom.y(), atom.z(), nucleusRadii[i] * 1.07f, 0x8F9EBC, .12f);
                    }
                    if (visible[3] && (model.configuration(i) == null || model.configuration(i).electrons() > 0)) {
                        particle(atom.x(), atom.y(), atom.z(), atom.element().radius() * .96f, colors[i], .09f);
                    }
                }
                particles.flip();
                envelopes = particles;
                particles = electrons;
                envelopeYaw = yaw;
                envelopePitch = pitch;
                envelopeFocus = focused;
                envelopesDirty = false;
            }
            detailDirty = false;
            GeometryPreparation.checkCancelled();
            return true;
        }

        /** Advances rendering phases step-by-step. No waits: a pending worker or exhausted budget leaves the original FBO intact. */
        public boolean advance() {
            if (closed) {
                throw new IllegalStateException("Atom export cursor is closed");
            }
            if (width <= 0 || height <= 0 || phase == 6) {
                return true;
            }
            if (preparation == null) {
                preparation = GeometryPreparation.submitForeground(this::prepareFrame);
                return false;
            }
            if (!preparation.isDone()) {
                return false;
            }
            try {
                preparation.get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CancellationException();
            } catch (ExecutionException failure) {
                throw new IllegalStateException("Cannot prepare atom export", failure.getCause());
            }
            var palette = exportPalette;
            if (phase == 1 && focused < 0 && !bonds.bonds().isEmpty() && links == null) {
                links = GpuMoleculeCache.beginExport(bonds, false, palette);
                if (links == null) {
                    return false;
                }
            }
            boolean delegated = phase == 1 && links != null;
            if (!delegated && !FrameWorkBudget.tryExport()) {
                return false;
            }
            long started = System.nanoTime();
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            int previousBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            int previousVao = GL.getCapabilities().OpenGL30 ? GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING) : 0;
            int previousMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPushMatrix();
            try {
                if (!GuiClip.intersect(0, 0, width, height)) {
                    return false;
                }
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL11.glDisable(GL11.GL_LIGHTING);
                GL11.glDisable(GL11.GL_CULL_FACE);
                GL11.glShadeModel(GL11.GL_SMOOTH);
                GL11.glDisable(GL11.GL_ALPHA_TEST);
                GL11.glEnable(GL11.GL_DEPTH_TEST);
                GL11.glDepthFunc(GL11.GL_LEQUAL);
                GL11.glDepthMask(true);
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                if (!depthStarted) {
                    GL11.glClearDepth(1);
                    GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
                    depthStarted = true;
                }
                if (phase == 0) {
                    ensureGpu();
                    phase = 1;
                    return false;
                }
                if (phase == 1) {
                    if (links != null && !links.advance(transform)) {
                        return false;
                    }
                    if (links != null) {
                        links.close();
                        links = null;
                    }
                    GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
                    phase = 2;
                    first = 0;
                    return false;
                }
                matrix.clear();
                transform.get(matrix);
                GL11.glMultMatrixf(matrix);
                boolean complete;
                if (phase == 2) {
                    complete = !visible[0] || particleRange(nucleusParticles, nucleusInstances, false);
                } else if (phase == 3) {
                    complete = !visible[1] || orbitRange();
                } else if (phase == 4) {
                    complete = !visible[2] || particleRange(particles, instances, false);
                } else {
                    GL11.glDepthMask(false);
                    GL11.glEnable(GL11.GL_CULL_FACE);
                    GL11.glCullFace(GL11.GL_BACK);
                    complete = particleRange(envelopes, envelopeInstances, true);
                }
                if (complete) {
                    phase++;
                    first = 0;
                }
                if (phase == 6) {
                    GL11.glDepthMask(true);
                    GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
                    rendered = true;
                }
                return phase == 6;
            } finally {
                GL11.glPopMatrix();
                GL11.glMatrixMode(previousMode);
                if (GL.getCapabilities().OpenGL30) {
                    GL30.glBindVertexArray(previousVao);
                }
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousBuffer);
                GL20.glUseProgram(previousProgram);
                GL11.glPopAttrib();
                if (!delegated) {
                    FrameWorkBudget.recordExport(System.nanoTime() - started);
                }
            }
        }

        /** Renders a particle range slice. */
        private boolean particleRange(FloatBuffer data, int buffer, boolean envelope) {
            int total = data.remaining() / STRIDE;
            if (first >= total) {
                return true;
            }
            int vertices = (envelope ? ENVELOPE_MESH : mesh).length / 6;
            int count = Math.min(total - first, Math.min(256, Math.max(1, 65535 / vertices)));
            FloatBuffer range = data.duplicate();
            range.position(data.position() + first * STRIDE).limit(data.position() + (first + count) * STRIDE);
            drawParticles(range.slice(), buffer, true, envelope);
            first += count;
            return first == total;
        }

        /** Renders an orbit line range slice. */
        private boolean orbitRange() {
            int total = orbitLines.remaining() / 3;
            if (first >= total) {
                return true;
            }
            int count = Math.min(total - first, 65534);
            GL20.glUseProgram(0);
            GL11.glDepthMask(false);
            GL11.glColor4f(.65f, .75f, .9f, .22f);
            // Original line-pair order and fixed-function color survive every slice.
            if (program != 0) {
                FloatBuffer range = orbitLines.duplicate();
                range.position(first * 3).limit((first + count) * 3);
                GL30.glBindVertexArray(orbitVao);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, orbitBuffer);
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER, range.slice(), GL15.GL_STREAM_DRAW);
                GL11.glDisableClientState(GL11.GL_COLOR_ARRAY);
                GL11.glDisableClientState(GL11.GL_NORMAL_ARRAY);
                GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
                GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
                GL11.glVertexPointer(3, GL11.GL_FLOAT, 0, 0L);
                GL11.glDrawArrays(GL11.GL_LINES, 0, count);
                GL30.glBindVertexArray(0);
            } else {
                GL11.glBegin(GL11.GL_LINES);
                for (int vertex = first * 3; vertex < (first + count) * 3; vertex += 3) {
                    GL11.glVertex3f(orbitLines.get(vertex), orbitLines.get(vertex + 1), orbitLines.get(vertex + 2));
                }
                GL11.glEnd();
            }
            GL11.glDepthMask(true);
            first += count;
            return first == total;
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
            if (preparation != null && !preparation.isDone()) {
                preparation.cancel(true);
            }
            if (links != null) {
                links.close();
                links = null;
            }
        }
    }

    /** Calculates view radius for a focused atom or full structure. */
    float viewRadius(int focusedAtom) {
        return focusedAtom < 0 ? definition.structure().radius() : inspectionRadius(focusedAtom) * 1.1f;
    }

    /** Calculates inspection radius for an atomic index. */
    private float inspectionRadius(int index) {
        float radius = definition.structure().atoms().get(index).element().radius();
        AtomicConfiguration configuration = model.configuration(index);
        if (configuration != null && configuration.electrons() == 0) {
            radius *= sharedNuclei.computeIfAbsent(configuration, NuclearGeometry::packing).radius();
        }
        return radius;
    }

    /** Picks the nearest projected centre with a forgiving hit area; depth resolves close overlaps. */
    public int pick(float x, float y) {
        int selected = -1;
        float best = Float.POSITIVE_INFINITY, closest = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < definition.structure().atoms().size(); i++) {
            if (focused >= 0 && focused != i) {
                continue;
            }
            var atom = definition.structure().atoms().get(i);
            transform.transformPosition(projected.set(atom.x(), atom.y(), atom.z()));
            float radius = Math.max(9, inspectionRadius(i) * drawScale + 4);
            float dx = x - projected.x, dy = y - projected.y;
            float score = (dx * dx + dy * dy) / (radius * radius);
            if (score <= 1 && (score < best - .03f || Math.abs(score - best) <= .03f && projected.z > closest)) {
                selected = i;
                best = score;
                closest = projected.z;
            }
        }
        return selected;
    }

    /** Appends a particle entry to the particle buffer. */
    private void particle(float x, float y, float z, float radius, int color, float alpha) {
        if (particles.remaining() < STRIDE) {
            FloatBuffer expanded = BufferUtils.createFloatBuffer(particles.capacity() * 2);
            particles.flip();
            expanded.put(particles);
            particles = expanded;
        }
        particles.put(x).put(y).put(z).put(radius).put(((color >> 16) & 255) / 255f)
                .put(((color >> 8) & 255) / 255f).put((color & 255) / 255f).put(alpha);
    }

    /** Ensures GPU shader programs and buffers are allocated and initialized. */
    private void ensureGpu() {
        if (attempted) {
            return;
        }
        attempted = true;
        fallbackBuffer = GL15.glGenBuffers();
        if (GL.getCapabilities().OpenGL30) {
            fallbackVao = GL30.glGenVertexArrays();
        }
        if (!GL.getCapabilities().OpenGL33) {
            return;
        }
        int vertex = 0, fragment = 0;
        try {
            vertex = shader(GL20.GL_VERTEX_SHADER, "#version 120\nattribute vec3 shape; attribute float light; attribute vec4 sphere; attribute vec4 tint; varying vec4 color; void main(){ color=vec4(tint.rgb*light,tint.a); gl_Position=gl_ModelViewProjectionMatrix*vec4(sphere.xyz+shape*sphere.w,1.0); }");
            fragment = shader(GL20.GL_FRAGMENT_SHADER, "#version 120\nvarying vec4 color; void main(){ gl_FragColor=color; }");
            program = GL20.glCreateProgram();
            GL20.glAttachShader(program, vertex);
            GL20.glAttachShader(program, fragment);
            GL20.glBindAttribLocation(program, 0, "shape");
            GL20.glBindAttribLocation(program, 1, "light");
            GL20.glBindAttribLocation(program, 2, "sphere");
            GL20.glBindAttribLocation(program, 3, "tint");
            GL20.glLinkProgram(program);
            if (GL.getCapabilities().OpenGL20 && GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
                throw new IllegalStateException(GL20.glGetProgramInfoLog(program));
            }
            shape = GL15.glGenBuffers();
            instances = GL15.glGenBuffers();
            vao = GL30.glGenVertexArrays();
            nucleusInstances = GL15.glGenBuffers();
            orbitBuffer = GL15.glGenBuffers();
            envelopeShape = GL15.glGenBuffers();
            orbitVao = GL30.glGenVertexArrays();
            envelopeInstances = GL15.glGenBuffers();
            FloatBuffer envelopes = BufferUtils.createFloatBuffer(ENVELOPE_MESH.length);
            envelopes.put(ENVELOPE_MESH).flip();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, envelopeShape);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, envelopes, GL15.GL_STATIC_DRAW);
            detailDirty = true;
            FloatBuffer data = BufferUtils.createFloatBuffer(mesh.length);
            data.put(mesh).flip();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, shape);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW);
        } catch (RuntimeException failure) {
            close();
            attempted = true;
            fallbackBuffer = GL15.glGenBuffers();
            if (GL.getCapabilities().OpenGL30) {
                fallbackVao = GL30.glGenVertexArrays();
            }
        } finally {
            if (vertex != 0) {
                GL20.glDeleteShader(vertex);
            }
            if (fragment != 0) {
                GL20.glDeleteShader(fragment);
            }
        }
    }

    /** Compiles a shader object from source. */
    private static int shader(int kind, String source) {
        int shader = GL20.glCreateShader(kind);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL.getCapabilities().OpenGL20 && GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
            String error = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException(error);
        }
        return shader;
    }

    /** Draws particle instances using hardware instancing or fallback rendering. */
    private void drawParticles(FloatBuffer data, int buffer, boolean upload, boolean envelope) {
        int count = data.remaining() / STRIDE;
        if (count == 0) {
            return;
        }
        float[] selectedMesh = envelope ? ENVELOPE_MESH : mesh;
        if (program != 0) {
            // Private reusable VAO keeps caller vertex attributes/divisors untouched.
            GL30.glBindVertexArray(vao);
            try {
                GL20.glUseProgram(program);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, envelope ? envelopeShape : shape);
                GL20.glEnableVertexAttribArray(0);
                GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 24, 0L);
                GL33.glVertexAttribDivisor(0, 0);
                GL20.glEnableVertexAttribArray(1);
                GL20.glVertexAttribPointer(1, 1, GL11.GL_FLOAT, false, 24, 12L);
                GL33.glVertexAttribDivisor(1, 0);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
                if (upload) {
                    GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, buffer == nucleusInstances ? GL15.GL_STATIC_DRAW : GL15.GL_STREAM_DRAW);
                }
                GL20.glEnableVertexAttribArray(2);
                GL20.glVertexAttribPointer(2, 4, GL11.GL_FLOAT, false, 32, 0L);
                GL33.glVertexAttribDivisor(2, 1);
                GL20.glEnableVertexAttribArray(3);
                GL20.glVertexAttribPointer(3, 4, GL11.GL_FLOAT, false, 32, 16L);
                GL33.glVertexAttribDivisor(3, 1);
                GL31.glDrawArraysInstanced(GL11.GL_TRIANGLES, 0, selectedMesh.length / 6, count);
            } finally {
                GL30.glBindVertexArray(0);
                GL20.glUseProgram(0);
            }
        } else {
            // Batch the same vertices on older GPUs instead of making a native call for every vertex.
            GL20.glUseProgram(0);
            if (fallbackVao != 0) {
                GL30.glBindVertexArray(fallbackVao);
            } else {
                GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
            }
            try {
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, fallbackBuffer);
                GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
                GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
                GL11.glDisableClientState(GL11.GL_NORMAL_ARRAY);
                GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
                GL11.glVertexPointer(3, GL11.GL_FLOAT, 28, 0L);
                GL11.glColorPointer(4, GL11.GL_FLOAT, 28, 12L);
                if (fallbackVertices == null) {
                    fallbackVertices = BufferUtils.createFloatBuffer(32760 * 7);
                }
                fallbackVertices.clear();
                for (int i = 0; i < count; i++) {
                    int offset = i * STRIDE;
                    float x = data.get(offset), y = data.get(offset + 1), z = data.get(offset + 2), r = data.get(offset + 3);
                    float red = data.get(offset + 4), green = data.get(offset + 5), blue = data.get(offset + 6), alpha = data.get(offset + 7);
                    for (int v = 0; v < selectedMesh.length; v += 6) {
                        if (fallbackVertices.remaining() < 7) {
                            flushFallback();
                        }
                        float light = selectedMesh[v + 3];
                        fallbackVertices.put(x + selectedMesh[v] * r).put(y + selectedMesh[v + 1] * r).put(z + selectedMesh[v + 2] * r)
                                .put(red * light).put(green * light).put(blue * light).put(alpha);
                    }
                }
                flushFallback();
            } finally {
                if (fallbackVao != 0) {
                    GL30.glBindVertexArray(0);
                } else {
                    GL11.glPopClientAttrib();
                }
            }
        }
    }

    /** Flushes buffered fallback vertex data. */
    private void flushFallback() {
        fallbackVertices.flip();
        if (fallbackVertices.hasRemaining()) {
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, fallbackVertices, GL15.GL_STREAM_DRAW);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, fallbackVertices.remaining() / 7);
        }
        fallbackVertices.clear();
    }

    /** Updates atomic detail levels and rebuilds buffers when necessary. */
    private void updateDetail() {
        boolean changed = false;
        var atoms = definition.structure().atoms();
        for (int i = 0; i < detail.length; i++) {
            GeometryPreparation.checkCancelled();
            float pixels = atoms.get(i).element().radius() * drawScale;
            byte next = (byte) (focused >= 0 && focused != i ? -1 : focused == i || pixels >= 32 ? 2 : pixels >= 12 ? 1 : 0);
            if (moleculeReveal) {
                next = (byte) (reveal[i] > 0 ? 2 : -1);
            }
            changed |= detail[i] != next;
            detail[i] = next;
        }
        if (!changed) {
            return;
        }
        FloatBuffer electrons = particles;
        particles = nucleusParticles;
        particles.clear();
        int orbitVertices = 0;
        for (int i = 0; i < detail.length; i++) {
            if (detail[i] >= 1 && model.configuration(i) != null) {
                for (int population : model.configuration(i).electronShells()) {
                    if (population > 0) {
                        orbitVertices += 64;
                    }
                }
            }
        }
        if (orbitLines.capacity() < orbitVertices * 3) {
            orbitLines = BufferUtils.createFloatBuffer(orbitVertices * 3);
        }
        orbitLines.clear();
        for (int i = 0; i < detail.length; i++) {
            if (detail[i] < 0) {
                continue;
            }
            GeometryPreparation.checkCancelled();
            var atom = atoms.get(i);
            var configuration = model.configuration(i);
            float r = atom.element().radius();
            nucleusRadii[i] = r * .2f;
            if (configuration == null) {
                particle(atom.x(), atom.y(), atom.z(), r * .2f, colors[i], 1);
                continue;
            }
            if (detail[i] < 2) {
                particle(atom.x(), atom.y(), atom.z(), r * .2f, 0xB49CCC, 1);
            } else {
                var packing = sharedNuclei.computeIfAbsent(configuration, NuclearGeometry::packing);
                float radius = r * packing.nucleonRadius();
                nucleusRadii[i] = r * packing.radius();
                if (nuclei.get(i) == null) {
                    nuclei.set(i, packing.nucleons());
                }
                for (var point : nuclei.get(i)) {
                    particle(atom.x() + point.x() * r, atom.y() + point.y() * r, atom.z() + point.z() * r,
                            radius, point.proton() ? 0xF36B72 : 0x6D9FEC, 1);
                }
            }
            if (detail[i] < 1) {
                continue;
            }
            for (int shell = 0; shell < configuration.electronShells().size(); shell++) {
                if (configuration.electronShells().get(shell) == 0) {
                    continue;
                }
                float radius = r * (.35f + .57f * (shell + 1) / configuration.electronShells().size());
                double tilt = (shell % 3 - 1) * .75;
                for (int vertex = 0; vertex < 32; vertex++) {
                    for (int end = 0; end < 2; end++) {
                        double angle = (vertex + end) * Math.PI / 16;
                        float y = (float) Math.sin(angle) * radius;
                        orbitLines.put(atom.x() + (float) Math.cos(angle) * radius).put(atom.y() + y * (float) Math.cos(tilt)).put(atom.z() + y * (float) Math.sin(tilt));
                    }
                }
            }
        }
        particles.flip();
        nucleusParticles = particles;
        particles = electrons;
        orbitLines.flip();
        detailDirty = true;
        envelopesDirty = true;
    }

    /** Draws electron orbit lines. */
    private void drawOrbits() {
        if (!orbitLines.hasRemaining()) {
            return;
        }
        GL20.glUseProgram(0);
        GL11.glDepthMask(false);
        GL11.glColor4f(.65f, .75f, .9f, .22f);
        if (program != 0) {
            try {
                // Fixed-function orbit lines need their own arrays, without instanced particle divisors.
                GL30.glBindVertexArray(orbitVao);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, orbitBuffer);
                if (detailDirty) {
                    GL15.glBufferData(GL15.GL_ARRAY_BUFFER, orbitLines, GL15.GL_STATIC_DRAW);
                }
                GL11.glDisableClientState(GL11.GL_COLOR_ARRAY);
                GL11.glDisableClientState(GL11.GL_NORMAL_ARRAY);
                GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
                GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
                GL11.glVertexPointer(3, GL11.GL_FLOAT, 0, 0L);
                GL11.glDrawArrays(GL11.GL_LINES, 0, orbitLines.remaining() / 3);
            } finally {
                GL30.glBindVertexArray(0);
            }
        } else {
            GL11.glBegin(GL11.GL_LINES);
            for (int vertex = 0; vertex < orbitLines.limit(); vertex += 3) {
                GL11.glVertex3f(orbitLines.get(vertex), orbitLines.get(vertex + 1), orbitLines.get(vertex + 2));
            }
            GL11.glEnd();
        }
        GL11.glDepthMask(true);
    }

    /** Calculates rotation angle for electron shells. */
    static double electronAngle(double seconds, int shell, double loopSeconds) {
        double speed = .65 + 1.0 / (shell + 1);
        if (loopSeconds <= 0) {
            return seconds * speed;
        }
        // Complete electron turns close the loop without a last-frame reset jump.
        long turns = Math.max(1, Math.round(loopSeconds * speed / (Math.PI * 2)));
        return (seconds % loopSeconds) / loopSeconds * turns * Math.PI * 2;
    }

    /**
     * Represents animated electron cloud distributions around atomic configurations.
     */
    private static final class ElectronCloud {
        private final AtomicConfiguration configuration;
        private final float[] positions;
        private long time = Long.MIN_VALUE;
        private double period = Double.NaN;

        /** Constructs a new {@link ElectronCloud}. */
        private ElectronCloud(AtomicConfiguration configuration) {
            this.configuration = configuration;
            positions = new float[configuration.electrons() * 3];
        }

        private void update(long animationNanos) {
            update(animationNanos, 0);
        }

        /** Updates electron positions for the given animation timestamp and loop duration. */
        private void update(long animationNanos, double loopSeconds) {
            if (time == animationNanos && period == loopSeconds) {
                return;
            }
            double seconds = animationNanos / 1_000_000_000.0;
            int index = 0;
            for (int shell = 0; shell < configuration.electronShells().size(); shell++) {
                int count = configuration.electronShells().get(shell);
                float orbit = .35f + .57f * (shell + 1) / configuration.electronShells().size();
                double tilt = (shell % 3 - 1) * .75;
                float tiltCos = (float) Math.cos(tilt), tiltSin = (float) Math.sin(tilt);
                for (int electron = 0; electron < count; electron++) {
                    double angle = electronAngle(seconds, shell, loopSeconds) + electron * Math.PI * 2 / Math.max(1, count);
                    float y = (float) Math.sin(angle) * orbit;
                    positions[index++] = (float) Math.cos(angle) * orbit;
                    positions[index++] = y * tiltCos;
                    positions[index++] = y * tiltSin;
                }
            }
            time = animationNanos;
            period = loopSeconds;
        }
    }

    /**
     * Generates a smoothly shaded octahedron subdivision mesh.
     * <p>32 triangles for subpixel particles, full mesh for envelopes.
     */
    static float[] particleMesh() {
        float[][] points = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        int[][] faces = {{0, 2, 4}, {2, 1, 4}, {1, 3, 4}, {3, 0, 4}, {2, 0, 5}, {1, 2, 5}, {3, 1, 5}, {0, 3, 5}};
        FloatBuffer result = FloatBuffer.allocate(32 * 3 * 6);
        for (int[] face : faces) {
            Vector3f a = new Vector3f(points[face[0]][0], points[face[0]][1], points[face[0]][2]);
            Vector3f b = new Vector3f(points[face[1]][0], points[face[1]][1], points[face[1]][2]);
            Vector3f c = new Vector3f(points[face[2]][0], points[face[2]][1], points[face[2]][2]);
            Vector3f ab = new Vector3f(a).add(b).normalize(), bc = new Vector3f(b).add(c).normalize(), ca = new Vector3f(c).add(a).normalize();
            for (Vector3f point : new Vector3f[]{a, ab, ca, ab, b, bc, ca, bc, c, ab, bc, ca}) {
                float brightness = .35f + .65f * Math.max(0, (-point.x + point.y - point.z) * .577f);
                result.put(point.x).put(point.y).put(point.z).put(brightness).put(0).put(0);
            }
        }
        return result.array();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        if (shape != 0) {
            GL15.glDeleteBuffers(shape);
        }
        if (instances != 0) {
            GL15.glDeleteBuffers(instances);
        }
        if (program != 0) {
            GL20.glDeleteProgram(program);
        }
        if (vao != 0) {
            GL30.glDeleteVertexArrays(vao);
        }
        if (nucleusInstances != 0) {
            GL15.glDeleteBuffers(nucleusInstances);
        }
        if (orbitBuffer != 0) {
            GL15.glDeleteBuffers(orbitBuffer);
        }
        if (envelopeShape != 0) {
            GL15.glDeleteBuffers(envelopeShape);
        }
        if (envelopeInstances != 0) {
            GL15.glDeleteBuffers(envelopeInstances);
        }
        if (fallbackBuffer != 0) {
            GL15.glDeleteBuffers(fallbackBuffer);
        }
        if (orbitVao != 0) {
            GL30.glDeleteVertexArrays(orbitVao);
        }
        if (fallbackVao != 0) {
            GL30.glDeleteVertexArrays(fallbackVao);
        }
        shape = instances = program = vao = nucleusInstances = orbitBuffer = orbitVao = envelopeShape = envelopeInstances = fallbackBuffer = fallbackVao = 0;
        attempted = false;
        rendered = false;
        detailDirty = true;
        envelopesDirty = true;
    }
}
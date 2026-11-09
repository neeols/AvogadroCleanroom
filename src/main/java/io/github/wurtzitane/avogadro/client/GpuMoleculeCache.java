package io.github.wurtzitane.avogadro.client;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import io.github.wurtzitane.avogadro.Avogadro;
import io.github.wurtzitane.avogadro.molecule.MoleculeStructure;

import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

/**
 * Manages render-thread owned, bounded GPU storage and VBO/VAO caching for molecules.
 * <p>
 * Render-thread owned, bounded GPU storage. The fixed-function VBO path needs no shaders.
 */
final class GpuMoleculeCache {

    /** Maximum number of cached GPU molecule entries. */
    private static final int MAX_ENTRIES = 256;

    /** Maximum cache storage limit in bytes (128 MiB). */
    private static final long MAX_BYTES = 128L * 1024 * 1024;

    /** Array of resident cache entries. */
    private static final Entry[] entries = new Entry[MAX_ENTRIES];

    /** Reusable float buffer for matrix transformations. */
    private static final FloatBuffer matrix = BufferUtils.createFloatBuffer(16);

    /** Active OpenGL capabilities context. */
    private static GLCapabilities context;

    /** LRU clock counter and total allocated cache byte tracker. */
    private static long clock, bytes;

    /** Shader program and shape geometry buffer identifiers. */
    private static int program, atomShape, cubeShape, roundShape, squareShape, kindUniform;

    /** Shape element indices and element counts arrays. */
    private static final int[] shapeIndices = new int[4], shapeCounts = new int[4];

    /** Flag tracking whether shader initialization has been attempted. */
    private static boolean shaderAttempted;

    private GpuMoleculeCache() {}

    /**
     * Renders a molecule using default settings.
     *
     * @param molecule molecule structure
     * @param transform transformation matrix
     * @param cubic whether cubic crystal rendering is enabled
     * @param palette element palette snapshot
     * @return {@code true} if successfully drawn, {@code false} otherwise
     */
    static boolean draw(MoleculeStructure molecule, Matrix4f transform, boolean cubic,
            ElementPalette.Snapshot palette) {
        return draw(molecule, transform, cubic, palette, false);
    }

    /** Renders a molecule with immediate preparation option. */
    static boolean draw(MoleculeStructure molecule, Matrix4f transform, boolean cubic, ElementPalette.Snapshot palette,
            boolean immediate) {
        return draw(molecule, transform, cubic, palette, immediate, MoleculeStyle.DEFAULT);
    }

    /** Renders a molecule with specified style settings. */
    static boolean draw(MoleculeStructure molecule, Matrix4f transform, boolean cubic, ElementPalette.Snapshot palette,
            boolean immediate, MoleculeStyle style) {
        checkContext();
        Entry entry = find(molecule, cubic, palette, style);
        if (entry == null) {
            entry = prepareEntry(molecule, cubic, palette, immediate, style);
        }
        if (entry == null) {
            return false;
        }

        // Resident VAOs already contain their vertex/buffer bindings. Most item draws
        // only need to bind them; saving every client array and buffer on each item
        // repeats the upload path's work even though no array state is being changed.
        boolean legacyArrays = entry.vao == 0 && entry.fixedVao == 0;
        int previousBuffer = legacyArrays ? GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING) : 0;
        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousVao = context.OpenGL30 ? GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING) : 0;
        int previousMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        int drawProgram = entry.vao != 0 ? program : 0;
        if (legacyArrays) {
            GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
        }
        if (previousMode != GL11.GL_MODELVIEW) {
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
        }
        GL11.glPushMatrix();
        try {
            matrix.clear();
            transform.get(matrix);
            GL11.glMultMatrixf(matrix);
            if (previousProgram != drawProgram) {
                GL20.glUseProgram(drawProgram);
            }
            if (entry.vao != 0) {
                GL20.glUniform1i(kindUniform, 0);
                GL30.glBindVertexArray(entry.vao);
                GL31.glDrawElementsInstanced(GL11.GL_TRIANGLES, shapeCounts[cubic ? 1 : 0], GL11.GL_UNSIGNED_INT, 0L,
                        entry.atomCount);
                if (entry.bondCount != 0) {
                    GL20.glUniform1i(kindUniform, 1);
                    GL30.glBindVertexArray(entry.bondVao);
                    GL31.glDrawElementsInstanced(GL11.GL_TRIANGLES, shapeCounts[cubic ? 3 : 2], GL11.GL_UNSIGNED_INT,
                            0L, entry.bondCount);
                }
            } else {
                if (entry.fixedVao != 0) {
                    GL30.glBindVertexArray(entry.fixedVao);
                } else {
                    configureFixed(entry);
                }
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, entry.atomCount);
            }
            return true;
        } finally {
            GL11.glPopMatrix();
            if (previousMode != GL11.GL_MODELVIEW) {
                GL11.glMatrixMode(previousMode);
            }
            if (context.OpenGL30) {
                GL30.glBindVertexArray(previousVao);
            }
            if (legacyArrays) {
                GL11.glPopClientAttrib();
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousBuffer);
            }
            if (previousProgram != drawProgram) {
                GL20.glUseProgram(previousProgram);
            }
        }
    }

    /**
     * Starts or advances preparation without drawing.
     * <p>
     * Starts/advances preparation without drawing; preview captures must wait for this.
     *
     * @param molecule molecule structure
     * @param cubic whether cubic crystal rendering is enabled
     * @param palette element palette snapshot
     * @return {@code true} if ready or preparation advanced
     */
    static boolean prepare(MoleculeStructure molecule, boolean cubic, ElementPalette.Snapshot palette) {
        checkContext();
        return find(molecule, cubic, palette) != null
                || prepareEntry(molecule, cubic, palette, false, MoleculeStyle.DEFAULT) != null;
    }

    /** Prepares and allocates an entry in the GPU cache. */
    private static Entry prepareEntry(MoleculeStructure molecule, boolean cubic, ElementPalette.Snapshot palette,
            boolean immediate, MoleculeStyle style) {
        int previousBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int previousVao = context.OpenGL30 ? GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING) : 0;
        GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
        try {
            return get(molecule, cubic, palette, immediate, style);
        } finally {
            if (context.OpenGL30) {
                GL30.glBindVertexArray(previousVao);
            }
            GL11.glPopClientAttrib();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousBuffer);
        }
    }

    /** Returns or creates a resident cache entry. */
    private static Entry get(MoleculeStructure molecule, boolean cubic, ElementPalette.Snapshot palette,
            boolean immediate, MoleculeStyle style) {
        Entry resident = find(molecule, cubic, palette, style);
        if (resident != null) {
            return resident;
        }
        for (Entry uploading : entries) {
            if (uploading != null && uploading.molecule == molecule && uploading.cubic == cubic
                    && uploading.palette == palette.revision() && uploading.style.equals(style)) {
                uploading.used = ++clock;
                return advance(uploading, immediate) ? uploading : null;
            }
        }
        boolean instanced = false;
        if (molecule.atoms().size() >= 32) {
            boolean initializing = !shaderAttempted;
            if (initializing && !immediate && !FrameWorkBudget.tryUpload(0)) {
                return null;
            }
            long started = System.nanoTime();
            try {
                instanced = instancingAvailable();
            } finally {
                if (initializing) {
                    FrameWorkBudget.recordUpload(System.nanoTime() - started);
                }
            }
        }
        InstanceData shared = null;
        if (instanced) {
            for (Entry other : entries) {
                if (other != null && other.ready && other.molecule == molecule && other.palette == palette.revision()
                        && other.style.equals(style) && other.instances != null) {
                    shared = other.instances;
                    break;
                }
            }
        }
        PreparedMoleculeGeometry.Job job = shared == null
                ? PreparedMoleculeGeometry.request(molecule, cubic, palette, instanced, style)
                : null;
        if (shared == null && job == null) {
            if (immediate) {
                throw new IllegalStateException("Geometry preparation is busy; try the export again");
            }
            return null;
        }
        PreparedMoleculeGeometry.Data data = job == null ? null : immediate ? job.await() : job.ready();
        if (shared == null && data == null) {
            return null;
        }
        if (!immediate && !FrameWorkBudget.tryUpload(0)) {
            return null;
        }
        long started = System.nanoTime();
        int slot = -1;
        for (int i = 0; i < entries.length; i++) {
            Entry candidate = entries[i];
            if (candidate != null && candidate.pins != 0) {
                continue;
            }
            if (slot < 0 || entries[slot] != null && (candidate == null || candidate.used < entries[slot].used)) {
                slot = i;
            }
        }
        if (slot < 0) {
            return null;
        }
        Entry entry = new Entry(molecule, cubic, palette.revision(), style);
        try {
            entry.bytes = shared == null ? data.bytes() : 0;
            if (entry.bytes > MAX_BYTES) {
                throw new IllegalArgumentException("Molecule geometry exceeds the 128 MiB GPU cache limit");
            }
            if (shared != null) {
                entry.instances = shared;
                shared.references++;
            }
            evict(slot);
            while (bytes + entry.bytes > MAX_BYTES) {
                int oldest = -1;
                for (int i = 0; i < entries.length; i++) {
                    if (entries[i] != null && entries[i].pins == 0
                            && (oldest < 0 || entries[i].used < entries[oldest].used)) {
                        oldest = i;
                    }
                }
                if (oldest < 0) {
                    entry.delete();
                    return null;
                }
                evict(oldest);
            }
            if (instanced) {
                entry.instances = shared;
                if (shared == null) {
                    entry.instances = new InstanceData();
                    entry.instances.references = 1;
                    entry.instances.buffer = allocate(data.atoms().remaining());
                    entry.instances.bonds = allocate(data.bonds().remaining());
                    entry.instances.atomCount = data.atomCount();
                    entry.instances.bondCount = data.bondCount();
                    entry.instances.bytes = entry.bytes;
                }
                entry.buffer = entry.instances.buffer;
                entry.bondBuffer = entry.instances.bonds;
                entry.atomCount = entry.instances.atomCount;
                entry.bondCount = entry.instances.bondCount;
            } else {
                entry.atomCount = data.atomCount();
                entry.buffer = allocate(data.atoms().remaining());
            }
            entry.upload = data;
            entry.preparation = job;
            entry.used = ++clock;
            entries[slot] = entry;
            bytes += entry.bytes;
        } catch (RuntimeException failure) {
            entry.delete();
            throw failure;
        } finally {
            FrameWorkBudget.recordUpload(System.nanoTime() - started);
        }
        return advance(entry, immediate) ? entry : null;
    }

    /** Finds a cached entry matching the molecule, cubic flag, and palette. */
    static Entry find(MoleculeStructure molecule, boolean cubic, ElementPalette.Snapshot palette) {
        return find(molecule, cubic, palette, MoleculeStyle.DEFAULT);
    }

    /** Finds a cached entry matching the molecule, cubic flag, palette, and style. */
    static Entry find(MoleculeStructure molecule, boolean cubic, ElementPalette.Snapshot palette, MoleculeStyle style) {
        for (Entry entry : entries) {
            if (entry != null && entry.ready && entry.molecule == molecule && entry.cubic == cubic
                    && entry.palette == palette.revision() && entry.style.equals(style)) {
                entry.used = ++clock;
                return entry;
            }
        }
        return null;
    }

    /** Checks whether a molecule geometry is ready in the GPU cache. */
    static boolean isReady(MoleculeStructure molecule, boolean cubic, ElementPalette.Snapshot palette) {
        for (Entry entry : entries) {
            if (entry != null && entry.ready && entry.molecule == molecule && entry.cubic == cubic
                    && entry.palette == palette.revision() && entry.style.equals(MoleculeStyle.DEFAULT)) {
                return true;
            }
        }
        return false;
    }

    /** Allocates static storage for a GPU buffer. */
    private static int allocate(int byteCount) {
        int buffer = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
        // Storage allocation is one driver operation; payload transfer is spread over frames.
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) byteCount, GL15.GL_STATIC_DRAW);
        return buffer;
    }

    /** Advances pending GPU data uploads and creates vertex array objects. */
    private static boolean advance(Entry entry, boolean immediate) {
        if (entry.upload != null) {
            ByteBuffer atoms = entry.upload.atoms(), bonds = entry.upload.bonds();
            while (entry.uploaded < atoms.remaining() + (long) bonds.remaining()) {
                boolean atom = entry.uploaded < atoms.remaining();
                ByteBuffer source = (atom ? atoms : bonds).duplicate();
                int offset = (int) (atom ? entry.uploaded : entry.uploaded - atoms.remaining());

                int count = Math.min(FrameWorkBudget.UPLOAD_CHUNK_BYTES, source.remaining() - offset);
                if (!immediate && !FrameWorkBudget.tryUpload(count)) {
                    return false;
                }
                long started = System.nanoTime();
                try {
                    source.position(offset).limit(offset + count);
                    GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, atom ? entry.buffer : entry.bondBuffer);
                    GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, offset, source);
                    entry.uploaded += count;
                } finally {
                    FrameWorkBudget.recordUpload(System.nanoTime() - started);
                }
            }
        }
        if (!immediate && !FrameWorkBudget.tryUpload(0)) {
            return false;
        }
        long started = System.nanoTime();
        try {
            if (entry.instances != null) {
                entry.vao = instanceVao(entry.cubic ? cubeShape : atomShape, shapeIndices[entry.cubic ? 1 : 0],
                        entry.buffer, false);
                entry.bondVao = instanceVao(entry.cubic ? squareShape : roundShape, shapeIndices[entry.cubic ? 3 : 2],
                        entry.bondBuffer, true);
            } else {
                if (context.OpenGL30) {
                    entry.fixedVao = GL30.glGenVertexArrays();
                    GL30.glBindVertexArray(entry.fixedVao);
                }
                configureFixed(entry);
            }
            entry.ready = true;
            entry.upload = null;
            if (entry.preparation != null) {
                PreparedMoleculeGeometry.release(entry.preparation);
            }
            entry.preparation = null;
            return true;
        } finally {
            FrameWorkBudget.recordUpload(System.nanoTime() - started);
        }
    }

    /** Configures fixed-function vertex attribute pointers. */
    private static void configureFixed(Entry entry) {
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, entry.buffer);
        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
        GL11.glDisableClientState(GL11.GL_NORMAL_ARRAY);
        GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL11.glVertexPointer(3, GL11.GL_FLOAT, 16, 0L);
        GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, 16, 12L);
    }

    /** Creates an instanced VAO setup. */
    private static int instanceVao(int shape, int elements, int instances, boolean cylinder) {
        int vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, elements);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, shape);
        attribute(0, 4, 24, 0, 0);
        attribute(1, 2, 24, 16, 0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, instances);
        int stride = (cylinder ? MoleculeInstances.BOND_STRIDE : MoleculeInstances.ATOM_STRIDE) * 4;
        attribute(2, 4, stride, 0, 1);
        attribute(3, 3, stride, 16, 1);
        if (cylinder) {
            attribute(4, 3, stride, 28, 1);
            attribute(5, 3, stride, 40, 1);
        }
        return vao;
    }

    /** Sets up vertex attributes and divisors. */
    private static void attribute(int index, int count, int stride, long offset, int divisor) {
        GL20.glEnableVertexAttribArray(index);
        GL20.glVertexAttribPointer(index, count, GL11.GL_FLOAT, false, stride, offset);
        GL33.glVertexAttribDivisor(index, divisor);
    }

    /**
     * Pins only the currently drawn model; callers keep their target/depth contents between ranges.
     * <p>
     * Pins only the currently drawn model; callers keep their target/depth contents between ranges.
     */
    static ExportCursor beginExport(MoleculeStructure molecule, boolean cubic, ElementPalette.Snapshot palette) {
        return beginExport(molecule, cubic, palette, MoleculeStyle.DEFAULT);
    }

    /** Begins an export cursor session with explicit styling. */
    static ExportCursor beginExport(MoleculeStructure molecule, boolean cubic, ElementPalette.Snapshot palette,
            MoleculeStyle style) {
        checkContext();
        Entry entry = find(molecule, cubic, palette, style);
        if (entry == null) {
            entry = prepareEntry(molecule, cubic, palette, false, style);
        }
        return entry == null ? null : new ExportCursor(entry);
    }

    /**
     * Represents an active export cursor for incremental media capture rendering.
     */
    static final class ExportCursor implements AutoCloseable {
        private final Entry entry;
        private final GLCapabilities owner = context;
        private int first, kind, privateVao;
        private boolean closed;

        /** Creates a export cursor and increments entry pin counts. */
        ExportCursor(Entry entry) {
            this.entry = entry;
            entry.pins++;
        }

        /** Advances the export rendering cursor. */
        boolean advance(Matrix4f transform) {
            if (closed) {
                return true;
            }
            if (owner != GL.getCapabilities() || !entry.ready) {
                throw new IllegalStateException("Export geometry was invalidated during capture");
            }
            int count = kind == 0 ? entry.atomCount : entry.bondCount;
            if (first == count) {
                kind++;
                first = 0;
                count = entry.bondCount;
            }
            if (kind >= (entry.vao == 0 ? 1 : 2) || count == 0) {
                close();
                return true;
            }
            if (!FrameWorkBudget.tryExport()) {
                return false;
            }
            long started = System.nanoTime();
            int previousBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            int previousVao = context.OpenGL30 ? GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING) : 0;
            int previousMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
            GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPushMatrix();
            try {
                matrix.clear();
                transform.get(matrix);
                GL11.glMultMatrixf(matrix);
                if (entry.vao != 0) {
                    boolean bond = kind != 0;
                    int shape = bond ? (entry.cubic ? 3 : 2) : (entry.cubic ? 1 : 0);
                    int batch = Math.min(count - first, Math.min(256, Math.max(1, 65535 / shapeCounts[shape])));
                    if (privateVao == 0) {
                        privateVao = GL30.glGenVertexArrays();
                    }
                    GL30.glBindVertexArray(privateVao);
                    GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, shapeIndices[shape]);
                    GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,
                            bond ? (entry.cubic ? squareShape : roundShape) : (entry.cubic ? cubeShape : atomShape));
                    attribute(0, 4, 24, 0, 0);
                    attribute(1, 2, 24, 16, 0);
                    GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, bond ? entry.bondBuffer : entry.buffer);
                    int stride = (bond ? MoleculeInstances.BOND_STRIDE : MoleculeInstances.ATOM_STRIDE) * 4;
                    long offset = (long) first * stride;
                    attribute(2, 4, stride, offset, 1);
                    attribute(3, 3, stride, offset + 16, 1);
                    if (bond) {
                        attribute(4, 3, stride, offset + 28, 1);
                        attribute(5, 3, stride, offset + 40, 1);
                    } else {
                        GL20.glDisableVertexAttribArray(4);
                        GL20.glDisableVertexAttribArray(5);
                    }
                    GL20.glUseProgram(program);
                    GL20.glUniform1i(kindUniform, kind);
                    GL31.glDrawElementsInstanced(GL11.GL_TRIANGLES, shapeCounts[shape], GL11.GL_UNSIGNED_INT, 0L,
                            batch);
                    first += batch;
                } else {
                    GL20.glUseProgram(0);
                    if (entry.fixedVao != 0) {
                        GL30.glBindVertexArray(entry.fixedVao);
                    } else {
                        configureFixed(entry);
                    }
                    int batch = Math.min(count - first, 65535);
                    GL11.glDrawArrays(GL11.GL_TRIANGLES, first, batch);
                    first += batch;
                }
                if (first == count && (entry.vao == 0 || kind == 1 || entry.bondCount == 0)) {
                    close();
                    return true;
                }
                return false;
            } finally {
                GL11.glPopMatrix();
                GL11.glMatrixMode(previousMode);
                if (context.OpenGL30) {
                    GL30.glBindVertexArray(previousVao);
                }
                GL11.glPopClientAttrib();
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousBuffer);
                GL20.glUseProgram(previousProgram);
                FrameWorkBudget.recordExport(System.nanoTime() - started);
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
            entry.pins--;
            if (privateVao != 0 && owner == GL.getCapabilities()) {
                GL30.glDeleteVertexArrays(privateVao);
            }
            privateVao = 0;
        }
    }

    /** Uploads vertex data to a new GPU buffer. */
    private static int upload(float[] data) {
        int buffer = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW);
        return buffer;
    }

    /** Checks and initializes OpenGL 3.3 instancing capabilities. */
    private static boolean instancingAvailable() {
        if (shaderAttempted) {
            return program != 0;
        }
        shaderAttempted = true;
        if (!context.OpenGL33) {
            return false;
        }
        int vertex = 0, fragment = 0;
        try {
            vertex = shader(GL20.GL_VERTEX_SHADER,
                    """
                            #version 120
                            attribute vec4 mesh;
                            attribute vec2 normal;
                            attribute vec4 origin;
                            attribute vec3 target;
                            attribute vec3 right;
                            attribute vec3 up;
                            uniform int cylinder;
                            void main() {
                                vec3 position; vec3 color; float light;
                                if(cylinder == 0) {
                                    position = origin.xyz + mesh.xyz * origin.w;
                                    light = mesh.w; color = target;
                                } else {
                                    position = (mesh.z == 0.0 ? origin.xyz : target) + (right * mesh.x + up * mesh.y) * origin.w;
                                    vec3 n = normalize(right * mesh.w + up * normal.x);
                                    light = .35 + .65 * max(0.0, dot(n,vec3(-.577,.577,-.577)));
                                    color = vec3(.8);
                                }
                                gl_FrontColor = vec4(floor(color * light * 255.0) / 255.0,1.0);
                                gl_Position = gl_ModelViewProjectionMatrix * vec4(position,1.0);
                            }
                            """);
            fragment = shader(GL20.GL_FRAGMENT_SHADER, """
                    #version 120
                    void main() { gl_FragColor = gl_Color; }
                    """);
            program = GL20.glCreateProgram();
            GL20.glAttachShader(program, vertex);
            GL20.glAttachShader(program, fragment);
            String[] names = {"mesh", "normal", "origin", "target", "right", "up"};
            for (int i = 0; i < names.length; i++) {
                GL20.glBindAttribLocation(program, i, names[i]);
            }
            GL20.glLinkProgram(program);
            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
                throw new IllegalStateException(GL20.glGetProgramInfoLog(program));
            }
            kindUniform = GL20.glGetUniformLocation(program, "cylinder");
            atomShape = uploadShape(MoleculeRenderer.atomMesh(false), 0);
            cubeShape = uploadShape(MoleculeRenderer.atomMesh(true), 1);
            roundShape = uploadShape(MoleculeRenderer.bondMesh(false), 2);
            squareShape = uploadShape(MoleculeRenderer.bondMesh(true), 3);
            Avogadro.LOGGER.info("Molecule rendering: persistent VBOs and OpenGL 3.3 instancing for large structures");
        } catch (RuntimeException failure) {
            deleteShared();
            Avogadro.LOGGER.warn("Molecule instancing unavailable; using persistent VBOs: {}", failure.getMessage());
        } finally {
            if (vertex != 0) {
                GL20.glDeleteShader(vertex);
            }
            if (fragment != 0) {
                GL20.glDeleteShader(fragment);
            }
        }
        return program != 0;
    }

    /** Compiles a shader source string. */
    private static int shader(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
            String error = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException(error);
        }
        return shader;
    }

    /** Verifies the current OpenGL context. */
    private static void checkContext() {
        GLCapabilities current = GL.getCapabilities();
        if (context == current) {
            return;
        }
        // Handles from a lost context must never be deleted in the replacement context.
        java.util.Arrays.fill(entries, null);
        bytes = clock = 0;
        program = atomShape = cubeShape = roundShape = squareShape = 0;
        shaderAttempted = false;
        java.util.Arrays.fill(shapeIndices, 0);
        java.util.Arrays.fill(shapeCounts, 0);
        context = current;
    }

    /** Clears the molecule cache and releases prepared geometry. */
    static void clear() {
        checkContext();
        PreparedMoleculeGeometry.clear();
        for (int i = 0; i < entries.length; i++) {
            evict(i);
        }
    }

    /** Destroys the molecule cache and shared resources. */
    static void destroy() {
        clear();
        deleteShared();
        shaderAttempted = false;
    }

    /** Evicts a cache entry at the specified index. */
    private static void evict(int index) {
        if (index < 0 || entries[index] == null) {
            return;
        }
        bytes -= entries[index].delete();
        entries[index] = null;
    }

    /** Deletes shared shader programs and shape buffers. */
    private static void deleteShared() {
        if (program != 0) {
            GL20.glDeleteProgram(program);
        }
        for (int id : new int[]{atomShape, cubeShape, roundShape, squareShape}) {
            if (id != 0) {
                GL15.glDeleteBuffers(id);
            }
        }
        for (int id : shapeIndices) {
            if (id != 0) {
                GL15.glDeleteBuffers(id);
            }
        }
        java.util.Arrays.fill(shapeIndices, 0);
        java.util.Arrays.fill(shapeCounts, 0);
        program = atomShape = cubeShape = roundShape = squareShape = 0;
    }

    /**
     * Represents a cached GPU molecule entry.
     */
    static final class Entry {
        final MoleculeStructure molecule;
        final boolean cubic;
        final long palette;
        final MoleculeStyle style;
        int buffer, bondBuffer, vao, bondVao, fixedVao, atomCount, bondCount;
        long bytes, used;
        long uploaded;
        boolean ready;
        int pins;
        PreparedMoleculeGeometry.Data upload;
        PreparedMoleculeGeometry.Job preparation;
        InstanceData instances;

        /** Creates a cache entry with default style. */
        Entry(MoleculeStructure molecule, boolean cubic, long palette) {
            this(molecule, cubic, palette, MoleculeStyle.DEFAULT);
        }

        /** Creates a cache entry with explicit style. */
        Entry(MoleculeStructure molecule, boolean cubic, long palette, MoleculeStyle style) {
            this.molecule = molecule;
            this.cubic = cubic;
            this.palette = palette;
            this.style = style;
        }

        /** Deletes underlying GPU buffers, VAOs, and frees allocated byte counters. */
        long delete() {
            ready = false;
            if (preparation != null) {
                PreparedMoleculeGeometry.release(preparation);
            }
            preparation = null;
            upload = null;
            long freed = bytes;
            if (instances != null) {
                freed = 0;
                if (--instances.references == 0) {
                    if (instances.buffer != 0) {
                        GL15.glDeleteBuffers(instances.buffer);
                    }
                    if (instances.bonds != 0) {
                        GL15.glDeleteBuffers(instances.bonds);
                    }
                    freed = instances.bytes;
                }
            } else if (buffer != 0) {
                GL15.glDeleteBuffers(buffer);
            }
            if (vao != 0) {
                GL30.glDeleteVertexArrays(vao);
            }
            if (bondVao != 0) {
                GL30.glDeleteVertexArrays(bondVao);
            }
            if (fixedVao != 0) {
                GL30.glDeleteVertexArrays(fixedVao);
            }
            return freed;
        }
    }

    /** Uploads shape geometry and indices. */
    private static int uploadShape(float[] data, int slot) {
        ByteBuffer raw = BufferUtils.createByteBuffer(data.length * 4);
        for (float value : data) {
            raw.putFloat(value);
        }
        IndexedMesh mesh = new IndexedMesh(raw.flip(), 24);
        int buffer = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, mesh.vertices, GL15.GL_STATIC_DRAW);
        shapeIndices[slot] = GL15.glGenBuffers();
        // Buffer storage is target independent. Upload through ARRAY_BUFFER so creating
        // shared primitives never mutates the caller's VAO element-buffer binding.
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, shapeIndices[slot]);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, mesh.indices, GL15.GL_STATIC_DRAW);
        shapeCounts[slot] = mesh.indices.remaining();
        return buffer;
    }

    /**
     * Holds shared instance VBO data across multiple entries.
     */
    private static final class InstanceData {
        int buffer, bonds, atomCount, bondCount, references;
        long bytes;
    }
}

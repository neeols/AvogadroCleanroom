package io.github.wurtzitane.avogadro.client;

import io.github.wurtzitane.avogadro.api.CrystalCell;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLCapabilities;

import java.nio.FloatBuffer;
import java.util.Arrays;

/**
 * Caches and renders crystal lattice outline edges.
 * <p>Cached cell edges share the atom renderer's camera, clipping and GL-state scope.
 */
final class CrystalOutline {

    /** Maximum number of cached outline entries. */
    private static final int MAX_ENTRIES = 32;

    /** Maximum cache memory size in bytes (2 MiB). */
    private static final long MAX_BYTES = 2L * 1024 * 1024;

    /** Cache array of resident outline entries. */
    private static final Entry[] CACHE = new Entry[MAX_ENTRIES];

    /** Reusable float buffer for transformation matrices. */
    private static final FloatBuffer MATRIX = BufferUtils.createFloatBuffer(16);

    /** Active OpenGL capabilities context. */
    private static GLCapabilities context;

    /** LRU clock counter and current cache byte size tracker. */
    private static long clock, bytes;

    /** Vertex coordinate array for the crystal outline. */
    private final float[] vertices;

    /**
     * Constructs a new {@link CrystalOutline} for the specified crystal cell and count.
     *
     * @param cell  crystal cell configuration
     * @param count repetition count per axis
     */
    CrystalOutline(CrystalCell cell, int count) {
        vertices = new float[3 * (count + 1) * (count + 1) * count * 6];
        int offset = 0;
        for (int axis = 0; axis < 3; axis++) {
            for (int i = 0; i <= count; i++) {
                for (int j = 0; j <= count; j++) {
                    for (int k = 0; k < count; k++) {
                        for (int end = 0; end < 2; end++) {
                            Vector3f p = switch (axis) {
                                case 0 -> cell.position(k + end, i, j);
                                case 1 -> cell.position(i, k + end, j);
                                default -> cell.position(i, j, k + end);
                            };
                            vertices[offset++] = p.x;
                            vertices[offset++] = p.y;
                            vertices[offset++] = p.z;
                        }
                    }
                }
            }
        }
    }

    /**
     * Renders the crystal outline using the given transformation matrix.
     *
     * @param transform transformation matrix
     */
    void draw(Matrix4fc transform) {
        checkContext();
        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int buffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int vao = context.OpenGL30 ? GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING) : 0;
        int matrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        GL11.glPushAttrib(GL11.GL_CURRENT_BIT | GL11.GL_LINE_BIT);
        GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        try {
            Entry entry = resident();
            GL20.glUseProgram(0);
            MATRIX.clear();
            transform.get(MATRIX);
            GL11.glMultMatrixf(MATRIX);
            GL11.glLineWidth(1.2f);
            GL11.glColor4f(.17f, .48f, .65f, .8f);
            if (entry.vao != 0) {
                GL30.glBindVertexArray(entry.vao);
            } else {
                configure(entry.buffer);
            }
            GL11.glDrawArrays(GL11.GL_LINES, 0, vertices.length / 3);
        } finally {
            GL11.glPopMatrix();
            GL11.glMatrixMode(matrixMode);
            if (context.OpenGL30) {
                GL30.glBindVertexArray(vao);
            }
            GL11.glPopClientAttrib();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
            GL20.glUseProgram(program);
            GL11.glPopAttrib();
        }
    }

    /** Retrieves or loads the outline entry into the GPU cache. */
    private Entry resident() {
        int slot = 0;
        for (int i = 0; i < CACHE.length; i++) {
            Entry entry = CACHE[i];
            if (entry != null && entry.outline == this) {
                entry.used = ++clock;
                return entry;
            }
            if (CACHE[slot] != null && (entry == null || entry.used < CACHE[slot].used)) {
                slot = i;
            }
        }
        long size = (long) vertices.length * Float.BYTES;
        if (size > MAX_BYTES) {
            throw new IllegalArgumentException("Crystal outline exceeds its 2 MiB cache limit");
        }
        evict(slot);
        while (bytes + size > MAX_BYTES) {
            int oldest = -1;
            for (int i = 0; i < CACHE.length; i++) {
                if (CACHE[i] != null && (oldest < 0 || CACHE[i].used < CACHE[oldest].used)) {
                    oldest = i;
                }
            }
            evict(oldest);
        }
        Entry entry = new Entry(this);
        try {
            if (context.OpenGL30) {
                entry.vao = GL30.glGenVertexArrays();
                GL30.glBindVertexArray(entry.vao);
            }
            entry.buffer = GL15.glGenBuffers();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, entry.buffer);
            FloatBuffer payload = BufferUtils.createFloatBuffer(vertices.length);
            payload.put(vertices).flip();
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, payload, GL15.GL_STATIC_DRAW);
            configure(entry.buffer);
            entry.used = ++clock;
            CACHE[slot] = entry;
            bytes += size;
            return entry;
        } catch (RuntimeException | Error failure) {
            entry.delete();
            throw failure;
        }
    }

    /** Configures vertex pointer states for rendering. */
    private static void configure(int buffer) {
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glDisableClientState(GL11.GL_COLOR_ARRAY);
        GL11.glDisableClientState(GL11.GL_NORMAL_ARRAY);
        GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL11.glVertexPointer(3, GL11.GL_FLOAT, 0, 0L);
    }

    /** Verifies the current OpenGL context. */
    private static void checkContext() {
        GLCapabilities current = GL.getCapabilities();
        if (current == context) {
            return;
        }
        // A replacement context may reuse names belonging to the discarded context.
        Arrays.fill(CACHE, null);
        clock = bytes = 0;
        context = current;
    }

    /** Called with the owning render context during reload or client teardown. */
    static void clearCache() {
        checkContext();
        for (int i = 0; i < CACHE.length; i++) {
            evict(i);
        }
    }

    /** Evicts a cache entry at the specified index. */
    private static void evict(int index) {
        Entry entry = CACHE[index];
        if (entry == null) {
            return;
        }
        entry.delete();
        bytes -= (long) entry.outline.vertices.length * Float.BYTES;
        CACHE[index] = null;
    }

    /**
     * Represents a cached outline GPU buffer entry.
     */
    private static final class Entry {
        final CrystalOutline outline;
        int buffer, vao;
        long used;

        /** Constructs a new cache entry. */
        Entry(CrystalOutline outline) {
            this.outline = outline;
        }

        /** Deletes underlying GPU buffers and vertex arrays. */
        void delete() {
            if (vao != 0) {
                GL30.glDeleteVertexArrays(vao);
            }
            if (buffer != 0) {
                GL15.glDeleteBuffers(buffer);
            }
        }
    }
}
package io.github.wurtzitane.avogadro.client.export;

import java.util.Map;
import java.util.WeakHashMap;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GLCapabilities;

/**
 * Utility class for caching and querying OpenGL graphics device limits per context.
 * <p>
 * No driver queries once a context has supplied its immutable limits.
 */
final class MediaGraphicsLimits {

    /** Weak map caching capability limits per OpenGL context. */
    private static final Map<GLCapabilities, Limits> CONTEXTS = new WeakHashMap<>();

    /**
     * Returns or computes the graphics limits for the current OpenGL context.
     *
     * @return cached or newly created {@link Limits}
     */
    static Limits current() {
        GLCapabilities context = GL.getCapabilities();
        Limits limits = CONTEXTS.get(context);
        if (limits == null) {
            limits = new Limits(GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE),
                    GL11.glGetInteger(GL13.GL_MAX_TEXTURE_UNITS));
            CONTEXTS.put(context, limits);
        }
        return limits;
    }

    /**
     * Checks whether asynchronous GPU readbacks are supported by the given context capabilities.
     *
     * @param context the OpenGL capabilities instance
     * @return {@code true} if asynchronous readbacks are supported, {@code false} otherwise
     */
    static boolean asynchronous(GLCapabilities context) {
        return asynchronous(
                context.OpenGL21 || context.GL_ARB_pixel_buffer_object || context.GL_EXT_pixel_buffer_object,
                context.OpenGL30 || context.GL_ARB_map_buffer_range, context.OpenGL32 || context.GL_ARB_sync)
                && context.glMapBufferRange != 0 && context.glFenceSync != 0 && context.glClientWaitSync != 0
                && context.glDeleteSync != 0;
    }

    /**
     * Validates asynchronous capability flags.
     *
     * @param pixelBuffers whether pixel buffer objects are supported
     * @param rangeMapping whether range mapping is supported
     * @param fences whether sync fences are supported
     * @return {@code true} if all flags are enabled
     */
    static boolean asynchronous(boolean pixelBuffers, boolean rangeMapping, boolean fences) {
        return pixelBuffers && rangeMapping && fences;
    }

    /**
     * Represents immutable hardware and driver graphics limits.
     *
     * @param textureSize maximum supported 2D texture size dimension
     * @param textureUnits number of available texture units
     */
    record Limits(int textureSize, int textureUnits) {
    }
}

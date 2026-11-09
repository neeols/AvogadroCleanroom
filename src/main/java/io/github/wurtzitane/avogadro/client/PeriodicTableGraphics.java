package io.github.wurtzitane.avogadro.client;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;

import org.lwjgl.opengl.*;

/** Offscreen artwork must leave Minecraft's graphics state and texture cache in agreement. */
final class PeriodicTableGraphics implements AutoCloseable {

    /** Stored matrix mode state active prior to graphics context capture. */
    private final int matrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);

    /** Stored active texture unit state prior to capture. */
    private final int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);

    /** Stored active GLSL shader program ID prior to capture. */
    private final int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);

    /** Flag indicating whether separate draw and read framebuffers are supported by the context. */
    private final boolean separateFramebuffers = GL.getCapabilities().OpenGL30
            || GL.getCapabilities().GL_ARB_framebuffer_object;

    /** Stored draw framebuffer binding ID. */
    private final int drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);

    /** Stored read framebuffer binding ID. */
    private final int readFramebuffer = separateFramebuffers
            ? GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING)
            : drawFramebuffer;

    /** Stored renderbuffer binding ID. */
    private final int renderbuffer = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);

    /** Stored pixel unpack buffer binding ID. */
    private final int unpackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);

    /** Stored array buffer binding ID. */
    private final int arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);

    /** Stored alpha test function state. */
    private final int alphaFunction = GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC);

    /** Stored alpha test reference value state. */
    private final float alphaReference = GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF);

    /** Stored blend source RGB function state. */
    private final int blendSourceRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);

    /** Stored blend destination RGB function state. */
    private final int blendDestinationRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);

    /** Stored blend source alpha function state. */
    private final int blendSourceAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);

    /** Stored blend destination alpha function state. */
    private final int blendDestinationAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);

    /** Stored alpha test enabled state flag. */
    private final boolean alpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);

    /** Stored blending enabled state flag. */
    private final boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);

    /** Stored current RGBA color state array. */
    private final float[] color = new float[4];

    /** Stored projection matrix array. */
    private final float[] projectionMatrix = new float[16];

    /** Stored texture matrix array. */
    private final float[] textureMatrix = new float[16];

    /** Stored modelview matrix array. */
    private final float[] modelViewMatrix = new float[16];

    /** Stored bound 2D texture ID. */
    private final int texture;

    /** Stored 2D texture enabled state flag. */
    private final boolean textureEnabled;

    /**
     * Creates a periodic table graphics state context wrapper, pushing attribute and client attribute stacks and
     * recording current OpenGL parameters.
     */
    PeriodicTableGraphics() {
        GL11.glGetFloatv(GL11.GL_CURRENT_COLOR, color);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushClientAttrib(GL11.GL_CLIENT_ALL_ATTRIB_BITS);
        activeTexture(GL13.GL_TEXTURE0);
        texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        textureEnabled = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        // Nested item artwork can exceed the shallow projection and texture matrix stacks.
        GL11.glGetFloatv(GL11.GL_PROJECTION_MATRIX, projectionMatrix);
        GL11.glGetFloatv(GL11.GL_TEXTURE_MATRIX, textureMatrix);
        GL11.glGetFloatv(GL11.GL_MODELVIEW_MATRIX, modelViewMatrix);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
    }

    /**
     * Sets the active texture unit for both state managers and OpenGL.
     *
     * @param unit texture unit constant (e.g., {@code GL13.GL_TEXTURE0})
     */
    static void activeTexture(int unit) {
        GlStateManager.setActiveTexture(unit);
        GL13.glActiveTexture(unit);
    }

    /**
     * Binds a 2D texture ID safely across GlStateManager and OpenGL contexts.
     *
     * @param texture texture ID to bind
     */
    static void bind(int texture) {
        activeTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GlStateManager.bindTexture(0);
        GlStateManager.bindTexture(texture);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
    }

    /** Configures standard text rendering states for artwork generation. */
    static void textState() {
        bind(0);
        GlStateManager.enableTexture2D();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GlStateManager.enableAlpha();
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GlStateManager.alphaFunc(GL11.GL_GREATER, .1f);
        GL11.glAlphaFunc(GL11.GL_GREATER, .1f);
        GlStateManager.enableBlend();
        GL11.glEnable(GL11.GL_BLEND);
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE,
                GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    /** Restores previously captured OpenGL states, attributes, matrices, and framebuffers upon closing. */
    @Override
    public void close() {
        activeTexture(GL13.GL_TEXTURE0);
        bind(texture);
        if (textureEnabled)
            GlStateManager.enableTexture2D();
        else
            GlStateManager.disableTexture2D();
        if (alpha)
            GlStateManager.enableAlpha();
        else
            GlStateManager.disableAlpha();
        if (blend)
            GlStateManager.enableBlend();
        else
            GlStateManager.disableBlend();
        GlStateManager.alphaFunc(alphaFunction, alphaReference);
        GlStateManager.tryBlendFuncSeparate(blendSourceRgb, blendDestinationRgb, blendSourceAlpha,
                blendDestinationAlpha);
        GlStateManager.color(color[0], color[1], color[2], color[3]);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glLoadMatrixf(modelViewMatrix);
        GL11.glMatrixMode(GL11.GL_TEXTURE);
        GL11.glLoadMatrixf(textureMatrix);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadMatrixf(projectionMatrix);
        GL11.glMatrixMode(matrixMode);
        if (separateFramebuffers) {
            OpenGlHelper.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
            OpenGlHelper.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
        } else
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, drawFramebuffer);
        OpenGlHelper.glBindRenderbuffer(OpenGlHelper.GL_RENDERBUFFER, renderbuffer);
        GL11.glPopClientAttrib();
        GL11.glPopAttrib();
        GL20.glUseProgram(program);
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpackBuffer);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer);
        activeTexture(activeTexture);
    }
}

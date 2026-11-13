package io.github.wurtzitane.avogadro.client;

import java.nio.ByteBuffer;

import io.github.wurtzitane.avogadro.client.compat.DemonicaRenderScope;

import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;

import org.lwjgl.opengl.*;

/** Generated once from the live resource-pack font, then shared by every poster and held item. */
public final class PeriodicTableArtwork {

    static final float WALL_OFFSET = .0005f - 1f / 32f;
    private static final int FRAME_COLOR = 0x696F62;

    /** Aspect ratio constant calculated from layout width and height units. */
    public static final float ASPECT = PeriodicTableLayout.WIDTH_UNITS / PeriodicTableLayout.HEIGHT_UNITS;

    /** OpenGL texture ID, framebuffer ID, and tracking revision counter. */
    private static int texture, framebuffer, revision;

    /** Active periodic table layout configuration. */
    private static PeriodicTableLayout.Layout layout;

    /** Element catalog revision tracker ID. */
    private static long elementRevision = -1;
    private static long themeRevision = -1;

    private PeriodicTableArtwork() {}

    /**
     * Returns the current artwork revision counter.
     *
     * @return current revision value
     */
    public static int revision() {
        return revision;
    }

    /**
     * Computes the current aspect ratio.
     *
     * @return aspect ratio float value
     */
    public static float aspect() {
        return PeriodicTableLayout.WIDTH_UNITS / PeriodicTableLayout.heightUnits();
    }

    /** Clears and deletes allocated framebuffers and textures, incrementing the revision. */
    public static void clear() {
        PeriodicTableIcon.clear();
        if (framebuffer != 0)
            OpenGlHelper.glDeleteFramebuffers(framebuffer);
        if (texture != 0)
            net.minecraft.client.renderer.GlStateManager.deleteTexture(texture);
        framebuffer = texture = 0;
        layout = null;
        revision++;
    }

    /**
     * Returns or generates the periodic table artwork texture.
     *
     * @return OpenGL texture ID
     */
    public static int texture() {
        long currentElements = io.github.wurtzitane.avogadro.api.ElementCatalog.revision();
        if (texture != 0 && elementRevision == currentElements && themeRevision == UiThemes.paintRevision())
            return texture;
        if (texture != 0)
            clear();
        elementRevision = currentElements;
        themeRevision = UiThemes.paintRevision();
        if (!OpenGlHelper.framebufferSupported)
            return 0;
        try (var scope = DemonicaRenderScope.open(); var ignored = new PeriodicTableGraphics()) {
            int width = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE) >= 2048 ? 2048 : 1024;
            int height = Math.round(width / aspect());
            GL20.glUseProgram(0);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            texture = GL11.glGenTextures();
            PeriodicTableGraphics.bind(texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0, GL11.GL_RGBA,
                    GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            framebuffer = OpenGlHelper.glGenFramebuffers();
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, framebuffer);
            OpenGlHelper.glFramebufferTexture2D(OpenGlHelper.GL_FRAMEBUFFER, OpenGlHelper.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, texture, 0);
            if (OpenGlHelper
                    .glCheckFramebufferStatus(OpenGlHelper.GL_FRAMEBUFFER) != OpenGlHelper.GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("Cannot create the periodic table artwork");
            GL11.glDrawBuffer(OpenGlHelper.GL_COLOR_ATTACHMENT0);
            GL11.glViewport(0, 0, width, height);
            GL11.glColorMask(true, true, true, true);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_FOG);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_COLOR_LOGIC_OP);
            GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
            GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
            // A poster can first be drawn from inside a clipped GUI or an item render.
            for (int plane = 0; plane < 6; plane++)
                GL11.glDisable(GL11.GL_CLIP_PLANE0 + plane);
            int units = GL11.glGetInteger(GL13.GL_MAX_TEXTURE_UNITS);
            for (int unit = 0; unit < units; unit++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL11.glDisable(GL11.GL_TEXTURE_GEN_S);
                GL11.glDisable(GL11.GL_TEXTURE_GEN_T);
                GL11.glDisable(GL11.GL_TEXTURE_GEN_R);
                GL11.glDisable(GL11.GL_TEXTURE_GEN_Q);
            }
            PeriodicTableGraphics.activeTexture(GL13.GL_TEXTURE0);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glLoadIdentity();
            GL11.glOrtho(0, width, height, 0, -100, 100);
            GL11.glMatrixMode(GL11.GL_TEXTURE);
            GL11.glLoadIdentity();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glLoadIdentity();
            layout = PeriodicTableLayout.fit(width, height);
            PeriodicTableRenderer.draw(layout, -1, -1);
            PeriodicTableGraphics.bind(texture);
            GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
        } catch (RuntimeException failure) {
            clear();
            throw failure;
        }
        return texture;
    }

    public static void drawBoard(float width, float height, boolean negativeFront) {
        int image = texture();
        if (image == 0)
            return;
        float fittedWidth = fittedWidth(width, height), fittedHeight = fittedWidth / aspect();
        float left = -fittedWidth / 2, right = fittedWidth / 2;
        float bottom = -fittedHeight / 2, top = fittedHeight / 2;
        float border = Math.min(width, height) * .0125f;
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        int cullFace = GL11.glGetInteger(GL11.GL_CULL_FACE_MODE);
        try {
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glEnable(GL11.GL_CULL_FACE);
            GL11.glCullFace(GL11.GL_BACK);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            worldSurface(false);
            quad(left - border, bottom - border, right + border, top + border, !negativeFront, FRAME_COLOR);
            quad(left - border, bottom - border, right + border, bottom, negativeFront, FRAME_COLOR);
            quad(left - border, top, right + border, top + border, negativeFront, FRAME_COLOR);
            quad(left - border, bottom, left, top, negativeFront, FRAME_COLOR);
            quad(right, bottom, right + border, top, negativeFront, FRAME_COLOR);
            PeriodicTableGraphics.bind(image);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            worldSurface(true);
            quad(left, bottom, right, top, negativeFront, 0xFFFFFF);
        } finally {
            GL11.glCullFace(cullFace);
            if (cull)
                GL11.glEnable(GL11.GL_CULL_FACE);
            else
                GL11.glDisable(GL11.GL_CULL_FACE);
        }
    }

    /**
     * Returns the current layout instance.
     *
     * @return layout instance
     */
    static PeriodicTableLayout.Layout layout() {
        return layout;
    }

    /**
     * Calculates fitted width dimension for the periodic table board.
     *
     * @param width total width
     * @param height total height
     * @return fitted width value
     */
    static float fittedWidth(float width, float height) {
        return Math.min(width - .075f, (height - .075f) * aspect());
    }

    /**
     * Renders a hover outline highlight around a specific element on the board.
     *
     * @param width board width
     * @param height board height
     * @param element atomic number of the element to highlight
     */
    static void drawHover(float width, float height, int element) {
        if (layout == null || element < 0)
            return;
        float fitWidth = fittedWidth(width, height), fitHeight = fitWidth / aspect();
        GL11.glPushMatrix();
        try {
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            worldSurface(false);
            // The wall-facing board's UV origin is bottom-left; layout coordinates start at the top.
            GL11.glDepthMask(false);
            GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
            GL11.glPolygonOffset(-1, -1);
            GL11.glNormal3f(0, 0, -1);
            GL11.glTranslatef(fitWidth / 2, fitHeight / 2, 0);
            GL11.glScalef(-fitWidth / layout.width(), -fitHeight / layout.height(), 1);
            PeriodicTableRenderer.hoverOutline(layout, element);
        } finally {
            GL11.glPopMatrix();
        }
    }

    private static void quad(float left, float bottom, float right, float top, boolean negativeFront, int color) {
        quad(left, bottom, right, top, negativeFront, color, false);
    }

    private static void quad(float left, float bottom, float right, float top, boolean negativeFront, int color,
            boolean flipVertical) {
        if (negativeFront) {
            float mirror = -left;
            left = -right;
            right = mirror;
        }
        float normal = negativeFront ? -1 : 1;
        float first = negativeFront ? right : left, last = negativeFront ? left : right;
        var tessellator = Tessellator.getInstance();
        var buffer = tessellator.getBuffer();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR_NORMAL);
        float bottomV = flipVertical ? 1 : 0, topV = flipVertical ? 0 : 1;
        vertex(buffer, first, bottom, 0, bottomV, normal, color);
        vertex(buffer, last, bottom, 1, bottomV, normal, color);
        vertex(buffer, last, top, 1, topV, normal, color);
        vertex(buffer, first, top, 0, topV, normal, color);
        tessellator.draw();
    }

    private static void vertex(net.minecraft.client.renderer.BufferBuilder buffer, float x, float y, float u, float v,
            float normal, int color) {
        buffer.pos(x, y, 0).tex(u, v).color(color >>> 16 & 255, color >>> 8 & 255, color & 255, 255)
                .normal(0, 0, normal).endVertex();
    }

    private static void worldSurface(boolean textured) {
        PeriodicTableGraphics.activeTexture(GL13.GL_TEXTURE1);
        boolean lightmap = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        PeriodicTableGraphics.activeTexture(GL13.GL_TEXTURE0);
        DemonicaRenderScope.worldSurface(textured, lightmap);
    }
}

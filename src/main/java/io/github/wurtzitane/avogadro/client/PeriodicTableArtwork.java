package io.github.wurtzitane.avogadro.client;

import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.opengl.*;

import java.nio.ByteBuffer;

/** Generated once from the live resource-pack font, then shared by every poster and held item. */
public final class PeriodicTableArtwork {

    /** Aspect ratio constant calculated from layout width and height units. */
    public static final float ASPECT = PeriodicTableLayout.WIDTH_UNITS / PeriodicTableLayout.HEIGHT_UNITS;

    /** OpenGL texture ID, framebuffer ID, and tracking revision counter. */
    private static int texture, framebuffer, revision;

    /** Active periodic table layout configuration. */
    private static PeriodicTableLayout.Layout layout;

    /** Element catalog revision tracker ID. */
    private static long elementRevision = -1;

    /** Private constructor to prevent instantiation of utility class. */
    private PeriodicTableArtwork() {}

    /**
     * Retrieves the current artwork revision counter.
     *
     * @return current revision value
     */
    public static int revision() { return revision; }

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
        if (framebuffer != 0) OpenGlHelper.glDeleteFramebuffers(framebuffer);
        if (texture != 0) net.minecraft.client.renderer.GlStateManager.deleteTexture(texture);
        framebuffer = texture = 0;
        layout = null;
        revision++;
    }

    /**
     * Retrieves or generates the periodic table artwork texture.
     *
     * @return OpenGL texture ID
     */
    public static int texture() {
        long currentElements = io.github.wurtzitane.avogadro.api.ElementCatalog.revision();
        if (texture != 0 && elementRevision == currentElements) return texture;
        if (texture != 0) clear();
        elementRevision = currentElements;
        if (!OpenGlHelper.framebufferSupported) return 0;
        try (var ignored = new PeriodicTableGraphics()) {
            int width = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE) >= 2048 ? 2048 : 1024;
            int height = Math.round(width / aspect());
            GL20.glUseProgram(0);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            texture = GL11.glGenTextures();
            PeriodicTableGraphics.bind(texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            framebuffer = OpenGlHelper.glGenFramebuffers();
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, framebuffer);
            OpenGlHelper.glFramebufferTexture2D(OpenGlHelper.GL_FRAMEBUFFER, OpenGlHelper.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, texture, 0);
            if (OpenGlHelper.glCheckFramebufferStatus(OpenGlHelper.GL_FRAMEBUFFER) != OpenGlHelper.GL_FRAMEBUFFER_COMPLETE)
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
            for (int plane = 0; plane < 6; plane++) GL11.glDisable(GL11.GL_CLIP_PLANE0 + plane);
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
            GL11.glMatrixMode(GL11.GL_PROJECTION); GL11.glLoadIdentity();
            GL11.glOrtho(0, width, height, 0, -100, 100);
            GL11.glMatrixMode(GL11.GL_TEXTURE); GL11.glLoadIdentity();
            GL11.glMatrixMode(GL11.GL_MODELVIEW); GL11.glLoadIdentity();
            layout = PeriodicTableLayout.fit(width, height);
            PeriodicTableRenderer.draw(layout, -1, -1);
        } catch (RuntimeException failure) {
            clear();
            throw failure;
        }
        return texture;
    }

    /** Artwork is fitted inside the backing, so a rounded block size never stretches its lettering. */
    public static void drawBoard(float width, float height, boolean negativeFront) {
        int image = texture();
        float front = negativeFront ? -.032f : .032f;
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glColor3ub((byte) 105, (byte) 111, (byte) 98);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex3f(-width / 2, -height / 2, -.031f);
        GL11.glVertex3f(width / 2, -height / 2, -.031f);
        GL11.glVertex3f(width / 2, height / 2, -.031f);
        GL11.glVertex3f(-width / 2, height / 2, -.031f);
        GL11.glVertex3f(-width / 2, -height / 2, .031f);
        GL11.glVertex3f(-width / 2, height / 2, .031f);
        GL11.glVertex3f(width / 2, height / 2, .031f);
        GL11.glVertex3f(width / 2, -height / 2, .031f);
        GL11.glEnd();
        paper(width - .035f, height - .035f, front, negativeFront, false);
        if (image == 0) return;
        float fitWidth = fittedWidth(width, height), fitHeight = fitWidth / aspect();
        PeriodicTableGraphics.bind(image);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1, 1, 1, 1);
        paper(fitWidth, fitHeight, front + (negativeFront ? -.001f : .001f), negativeFront, true);
    }

    /**
     * Retrieves the current layout instance.
     *
     * @return layout instance
     */
    static PeriodicTableLayout.Layout layout() { return layout; }

    /**
     * Calculates fitted width dimension for the periodic table board.
     *
     * @param width  total width
     * @param height total height
     * @return fitted width value
     */
    static float fittedWidth(float width, float height) {
        return Math.min(width - .075f, (height - .075f) * aspect());
    }

    /**
     * Renders a hover outline highlight around a specific element on the board.
     *
     * @param width  board width
     * @param height board height
     * @param element atomic number of the element to highlight
     */
    static void drawHover(float width, float height, int element) {
        if (layout == null || element <= 0) return;
        float fitWidth = fittedWidth(width, height), fitHeight = fitWidth / aspect();
        GL11.glPushMatrix();
        try {
            // The wall-facing board's UV origin is bottom-left; layout coordinates start at the top.
            GL11.glTranslatef(fitWidth / 2, fitHeight / 2, -.034f);
            GL11.glScalef(-fitWidth / layout.width(), -fitHeight / layout.height(), 1);
            PeriodicTableRenderer.hoverOutline(layout, element);
        } finally {
            GL11.glPopMatrix();
        }
    }

    /** Renders paper backing or textured poster quads. */
    private static void paper(float width, float height, float z, boolean negativeFront, boolean textured) {
        if (!textured) {
            int color = ElementCard.PAPER;
            GL11.glColor4ub((byte) (color >>> 16), (byte) (color >>> 8), (byte) color, (byte) 255);
        }
        float left = negativeFront ? width / 2 : -width / 2;
        float right = -left;
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glNormal3f(0, 0, negativeFront ? -1 : 1);
        GL11.glTexCoord2f(0, 0); GL11.glVertex3f(left, -height / 2, z);
        GL11.glTexCoord2f(1, 0); GL11.glVertex3f(right, -height / 2, z);
        GL11.glTexCoord2f(1, 1); GL11.glVertex3f(right, height / 2, z);
        GL11.glTexCoord2f(0, 1); GL11.glVertex3f(left, height / 2, z);
        GL11.glEnd();
    }
}
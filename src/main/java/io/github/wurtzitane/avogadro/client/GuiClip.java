package io.github.wurtzitane.avogadro.client;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * Converts the current GUI coordinate system to framebuffer pixels, including GUI scale and scissor tests.
 * <p>Converts the current GUI coordinate system to framebuffer pixels, including GUI scale.
 */
final class GuiClip {

    /** Projection transformation matrix. */
    private static final Matrix4f projection = new Matrix4f();

    /** Modelview transformation matrix. */
    private static final Matrix4f modelview = new Matrix4f();

    /** Reusable vector for coordinate transformation. */
    private static final Vector4f point = new Vector4f();

    /** Reusable float buffer for matrix queries. */
    private static final FloatBuffer values = BufferUtils.createFloatBuffer(16);

    /** Reusable integer buffer for current viewport bounds. */
    private static final IntBuffer viewport = BufferUtils.createIntBuffer(4);

    /** Reusable integer buffer for previous scissor box bounds. */
    private static final IntBuffer previous = BufferUtils.createIntBuffer(4);

    /** Calculated pixel scale factor. */
    static float pixelScale = 1;

    /** Private constructor to prevent instantiation of utility class. */
    private GuiClip() {}

    /**
     * Intersects the current clip region with the specified GUI rectangle bounds,
     * updating the OpenGL scissor box.
     *
     * @param x0 left x coordinate
     * @param y0 top y coordinate
     * @param x1 right x coordinate
     * @param y1 bottom y coordinate
     * @return {@code true} if the resulting scissor box is non-empty, {@code false} otherwise
     */
    static boolean intersect(float x0, float y0, float x1, float y1) {
        values.clear();
        GL11.glGetFloatv(GL11.GL_PROJECTION_MATRIX, values);
        Matrix4f transform = projection.set(values);
        values.clear();
        GL11.glGetFloatv(GL11.GL_MODELVIEW_MATRIX, values);
        transform.mul(modelview.set(values));
        viewport.clear();
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < 4; i++) {
            point.set((i & 1) == 0 ? x0 : x1, (i & 2) == 0 ? y0 : y1, 0, 1);
            transform.transform(point);
            float px = viewport.get(0) + (point.x / point.w + 1) * viewport.get(2) / 2f;
            float py = viewport.get(1) + (point.y / point.w + 1) * viewport.get(3) / 2f;
            minX = Math.min(minX, px);
            maxX = Math.max(maxX, px);
            minY = Math.min(minY, py);
            maxY = Math.max(maxY, py);
        }
        pixelScale = Math.max((maxX - minX) / Math.max(1, x1 - x0), (maxY - minY) / Math.max(1, y1 - y0));
        int left = Math.max(viewport.get(0), (int) Math.floor(minX));
        int bottom = Math.max(viewport.get(1), (int) Math.floor(minY));
        int right = Math.min(viewport.get(0) + viewport.get(2), (int) Math.ceil(maxX));
        int top = Math.min(viewport.get(1) + viewport.get(3), (int) Math.ceil(maxY));
        if (GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)) {
            previous.clear();
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, previous);
            left = Math.max(left, previous.get(0));
            bottom = Math.max(bottom, previous.get(1));
            right = Math.min(right, previous.get(0) + previous.get(2));
            top = Math.min(top, previous.get(1) + previous.get(3));
        }
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(left, bottom, Math.max(0, right - left), Math.max(0, top - bottom));
        return right > left && top > bottom;
    }
}
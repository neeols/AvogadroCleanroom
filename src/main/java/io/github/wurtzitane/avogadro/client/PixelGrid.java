package io.github.wurtzitane.avogadro.client;

import java.nio.FloatBuffer;

import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;

/**
 * Record representing a pixel grid capture for aligning and snapping pixel font text rendering.
 *
 * @param xScale horizontal scale factor
 * @param yScale vertical scale factor
 * @param xOrigin horizontal screen coordinate origin
 * @param yOrigin vertical screen coordinate origin
 */
record PixelGrid(float xScale, float yScale, float xOrigin, float yOrigin) {

    /**
     * Returns the absolute pixel scale factor.
     *
     * @return scale value
     */
    float scale() {
        return Math.abs(xScale);
    }

    /**
     * Snaps an x coordinate to the nearest pixel grid boundary.
     *
     * @param x input x coordinate
     * @return snapped x coordinate
     */
    float snapX(float x) {
        return (Math.round(xOrigin + x * xScale) - xOrigin) / xScale;
    }

    /**
     * Snaps a y coordinate to the nearest pixel grid boundary.
     *
     * @param y input y coordinate
     * @return snapped y coordinate
     */
    float snapY(float y) {
        return (Math.round(yOrigin + y * yScale) - yOrigin) / yScale;
    }

    /**
     * Captures the current OpenGL transformation matrices and viewport to construct a aligned pixel grid.
     *
     * @return constructed {@link PixelGrid}, or {@code null} if conditions or transforms are non-uniform/invalid
     */
    static PixelGrid capture() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer model = stack.mallocFloat(16), projection = stack.mallocFloat(16);
            var viewport = stack.mallocInt(4);
            GL11.glGetFloatv(GL11.GL_MODELVIEW_MATRIX, model);
            GL11.glGetFloatv(GL11.GL_PROJECTION_MATRIX, projection);
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            float w = product(projection, model, 3, 3);
            if (!Float.isFinite(w) || Math.abs(w) < 1e-6f || Math.abs(product(projection, model, 3, 0)) > 1e-6f
                    || Math.abs(product(projection, model, 3, 1)) > 1e-6f)
                return null;
            float x = product(projection, model, 0, 0) / w * viewport.get(2) * .5f;
            float y = product(projection, model, 1, 1) / w * viewport.get(3) * .5f;
            float crossX = product(projection, model, 0, 1) / w * viewport.get(2) * .5f;
            float crossY = product(projection, model, 1, 0) / w * viewport.get(3) * .5f;
            if (!Float.isFinite(x) || !Float.isFinite(y) || Math.abs(x) < 1e-6f
                    || Math.abs(Math.abs(x) - Math.abs(y)) > Math.abs(x) * .0001f
                    || Math.abs(crossX) + Math.abs(crossY) > .0001f)
                return null;
            float originX = viewport.get(0) + (product(projection, model, 0, 3) / w + 1) * viewport.get(2) * .5f;
            float originY = viewport.get(1) + (product(projection, model, 1, 3) / w + 1) * viewport.get(3) * .5f;
            return new PixelGrid(x, y, originX, originY);
        }
    }

    /**
     * Computes the dot product of a specific matrix row and column from projection and modelview buffers.
     *
     * @param left projection matrix buffer
     * @param right modelview matrix buffer
     * @param row row index
     * @param column column index
     * @return computed product value
     */
    private static float product(FloatBuffer left, FloatBuffer right, int row, int column) {
        float result = 0;
        for (int i = 0; i < 4; i++)
            result += left.get(i * 4 + row) * right.get(column * 4 + i);
        return result;
    }
}

package io.github.wurtzitane.avogadro.client;

import org.lwjgl.opengl.GL11;

/**
 * Renders reference-card symbols in square bounds without changing their proportions.
 */
public final class ElementCardIcons {

    /** Default stroke thickness factor. */
    private static final float STROKE = 0.045f;

    /** Active pixel grid alignment capture instance. */
    private static PixelGrid grid;

    /** Current stroke thickness value. */
    private static float stroke = STROKE;

    /** Private constructor to prevent instantiation of utility class. */
    private ElementCardIcons() {}

    /**
     * Draws an oxide character icon code within the specified bounds.
     *
     * @param code  oxide property code
     * @param x     x coordinate position
     * @param y     y coordinate position
     * @param size  icon square dimension size
     * @param color ARGB color integer
     */
    public static void drawOxide(String code, float x, float y, float size, int color) {
        begin(x, y, size, color);
        try {
            switch (code) {
                case "sb" -> disc(0.5f, 0.5f, 0.36f, 0, Math.PI * 2);
                case "wb" -> disc(0.5f, 0.5f, 0.36f, -Math.PI / 2, Math.PI / 2);
                case "eq" -> {
                    disc(0.5f, 0.5f, 0.36f, -Math.PI / 2, Math.PI / 2);
                    arc(0.5f, 0.5f, 0.36f, 0, Math.PI * 2);
                }
                case "wa" -> {
                    arc(0.5f, 0.5f, 0.36f, Math.PI / 2, Math.PI * 1.5);
                    line(0.5f, 0.14f, 0.5f, 0.86f);
                }
                case "sa" -> arc(0.5f, 0.5f, 0.36f, 0, Math.PI * 2);
                case "none" -> {
                    arc(0.5f, 0.5f, 0.36f, 0, Math.PI * 2);
                    line(0.25f, 0.75f, 0.75f, 0.25f);
                }
                default -> { }
            }
        } finally {
            end();
        }
    }

    /**
     * Draws a crystal structure icon code within the specified bounds.
     *
     * @param code  crystal structure code
     * @param x     x coordinate position
     * @param y     y coordinate position
     * @param size  icon square dimension size
     * @param color ARGB color integer
     */
    public static void drawCrystal(String code, float x, float y, float size, int color) {
        begin(x, y, size, color);
        try {
            switch (code) {
                case "hex", "hcp", "rhomb" -> {
                    float[][] vertices = {
                            {0.5f, 0.05f}, {0.89f, 0.275f}, {0.89f, 0.725f},
                            {0.5f, 0.95f}, {0.11f, 0.725f}, {0.11f, 0.275f}
                    };
                    polygon(vertices);
                    if (!code.equals("rhomb")) {
                        // Three diagonals meet at the centre, giving six radial spokes.
                        for (int i = 0; i < 3; i++) {
                            line(vertices[i], vertices[i + 3]);
                        }
                    } else {
                        line(vertices[0], vertices[2]);
                        line(vertices[2], vertices[4]);
                        line(vertices[4], vertices[0]);
                    }
                }
                case "diamond" -> {
                    float[][] vertices = {
                            {0.10f, 0.08f}, {0.83f, 0.26f}, {0.96f, 0.95f}, {0.23f, 0.80f}
                    };
                    polygon(vertices);
                    for (float[] vertex : vertices) {
                        line(vertex[0], vertex[1], 0.58f, 0.68f);
                    }
                }
                case "mono" -> polygon(new float[][] {
                        {0.34f, 0.06f}, {0.66f, 0.06f}, {0.82f, 0.5f},
                        {0.66f, 0.94f}, {0.34f, 0.94f}, {0.18f, 0.5f}
                });
                case "bcc", "neutron-cluster" -> bodyCentredCube();
                case "fcc" -> {
                    visibleCube(0.07f, 0.07f, 0.23f, 0.23f, 0.70f, 0.70f);
                    disc(0.58f, 0.58f, 0.105f, 0, Math.PI * 2);
                }
                case "cubic" -> visibleCube(0.25f, 0.06f, 0.07f, 0.24f, 0.70f, 0.70f);
                case "ortho" -> visibleCube(0.27f, 0.04f, 0.08f, 0.23f, 0.59f, 0.72f);
                case "tetr" -> visibleCube(0.46f, 0.04f, 0.27f, 0.23f, 0.31f, 0.72f);
                default -> { }
            }
        } finally {
            end();
        }
    }

    /**
     * Draws a reference state icon code within the specified bounds.
     *
     * @param code  reference state code
     * @param x     x coordinate position
     * @param y     y coordinate position
     * @param size  icon square dimension size
     * @param color ARGB color integer
     */
    public static void drawState(String code, float x, float y, float size, int color) {
        begin(x, y, size, color);
        try {
            switch (code) {
                case "solid" -> line(0.10f, 0.5f, 0.90f, 0.5f);
                case "synthetic" -> {
                    line(0.10f, 0.43f, 0.90f, 0.43f);
                    line(0.10f, 0.57f, 0.90f, 0.57f);
                }
                case "gas" -> {
                    for (int i = 0; i < 5; i++) {
                        float left = 0.10f + i * 0.17f;
                        line(left, 0.5f, left + 0.10f, 0.5f);
                    }
                }
                case "liquid" -> {
                    for (int i = 0; i < 64; i++) {
                        float left = 0.1f + 0.8f * i / 64;
                        float right = 0.1f + 0.8f * (i + 1) / 64;
                        line(left, wave(i / 64.0), right, wave((i + 1) / 64.0));
                    }
                }
                default -> { }
            }
        } finally {
            end();
        }
    }

    /** Calculates wave height for liquid state icons. */
    private static float wave(double position) {
        return 0.5f + (float) Math.sin(position * Math.PI * 6) * 0.035f;
    }

    /** Draws a 3D wireframe visible cube structure. */
    private static void visibleCube(float backX, float backY, float frontX, float frontY,
                                    float width, float height) {
        polygon(new float[][] {
                {frontX, frontY}, {frontX + width, frontY},
                {frontX + width, frontY + height}, {frontX, frontY + height}
        });
        line(frontX, frontY, backX, backY);
        line(backX, backY, backX + width, backY);
        line(backX + width, backY, frontX + width, frontY);
        if (backX < frontX) {
            line(backX, backY, backX, backY + height);
            line(backX, backY + height, frontX, frontY + height);
        } else {
            line(backX + width, backY, backX + width, backY + height);
            line(backX + width, backY + height, frontX + width, frontY + height);
        }
    }

    /** Draws a body-centred cubic lattice icon. */
    private static void bodyCentredCube() {
        float[][] back = {{0.08f, 0.08f}, {0.74f, 0.08f}, {0.74f, 0.74f}, {0.08f, 0.74f}};
        float[][] front = {{0.26f, 0.26f}, {0.92f, 0.26f}, {0.92f, 0.92f}, {0.26f, 0.92f}};
        polygon(back);
        polygon(front);
        for (int i = 0; i < 4; i++) {
            line(back[i], front[i]);
            disc(back[i][0], back[i][1], 0.035f, 0, Math.PI * 2);
            disc(front[i][0], front[i][1], 0.035f, 0, Math.PI * 2);
        }
        disc(0.5f, 0.5f, 0.09f, 0, Math.PI * 2);
    }

    /** Draws a closed polygon from an array of vertices. */
    private static void polygon(float[][] vertices) {
        for (int i = 0; i < vertices.length; i++) {
            line(vertices[i], vertices[(i + 1) % vertices.length]);
        }
    }

    /** Draws a line between two point arrays. */
    private static void line(float[] from, float[] to) {
        line(from[0], from[1], to[0], to[1]);
    }

    /** Draws a line segment with explicit coordinates. */
    private static void line(float x1, float y1, float x2, float y2) {
        float length = (float) Math.hypot(x2 - x1, y2 - y1);
        if (length == 0) {
            return;
        }
        if (grid != null && y1 == y2) {
            float left = grid.snapX(Math.min(x1, x2)), top = grid.snapY(y1 - stroke / 2);
            rectangle(left, top, pixelLength(length), stroke);
            return;
        }
        if (grid != null && x1 == x2) {
            float left = grid.snapX(x1 - stroke / 2), top = grid.snapY(Math.min(y1, y2));
            rectangle(left, top, stroke, pixelLength(length));
            return;
        }
        float normalX = -(y2 - y1) / length * stroke / 2;
        float normalY = (x2 - x1) / length * stroke / 2;
        vertex(x1 + normalX, y1 + normalY);
        vertex(x1 - normalX, y1 - normalY);
        vertex(x2 - normalX, y2 - normalY);
        vertex(x1 + normalX, y1 + normalY);
        vertex(x2 - normalX, y2 - normalY);
        vertex(x2 + normalX, y2 + normalY);
    }

    /** Computes pixel-snapped length. */
    private static float pixelLength(float length) {
        return Math.max(1, Math.round(length * grid.scale())) / grid.scale();
    }

    /** Draws a filled rectangle region. */
    private static void rectangle(float x, float y, float width, float height) {
        vertex(x, y);
        vertex(x, y + height);
        vertex(x + width, y + height);
        vertex(x, y);
        vertex(x + width, y + height);
        vertex(x + width, y);
    }

    /** Draws a filled circular disc segment. */
    private static void disc(float x, float y, float radius, double start, double finish) {
        for (int i = 0; i < 64; i++) {
            double a = start + (finish - start) * i / 64;
            double b = start + (finish - start) * (i + 1) / 64;
            vertex(x, y);
            vertex(x + (float) Math.cos(a) * radius, y + (float) Math.sin(a) * radius);
            vertex(x + (float) Math.cos(b) * radius, y + (float) Math.sin(b) * radius);
        }
    }

    /** Draws an arc outline segment. */
    private static void arc(float x, float y, float radius, double start, double finish) {
        for (int i = 0; i < 64; i++) {
            double a = start + (finish - start) * i / 64;
            double b = start + (finish - start) * (i + 1) / 64;
            line(x + (float) Math.cos(a) * radius, y + (float) Math.sin(a) * radius,
                    x + (float) Math.cos(b) * radius, y + (float) Math.sin(b) * radius);
        }
    }

    /** Emits a 2D vertex. */
    private static void vertex(float x, float y) {
        GL11.glVertex2f(x, y);
    }

    /** Initializes rendering states before drawing an icon. */
    private static void begin(float x, float y, float size, int color) {
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_POLYGON_SMOOTH);
        GL11.glColor4f(((color >> 16) & 255) / 255f, ((color >> 8) & 255) / 255f,
                (color & 255) / 255f, 1);
        GL11.glPushMatrix();
        GL11.glTranslatef(x, y, 0);
        GL11.glScalef(size, size, 1);
        grid = PixelGrid.capture();
        stroke = grid == null ? STROKE : pixelLength(STROKE);
        // Use one whole-pixel width for every stroke in this icon.
        GL11.glBegin(GL11.GL_TRIANGLES);
    }

    /** Restores rendering states after drawing an icon. */
    private static void end() {
        GL11.glEnd();
        GL11.glPopMatrix();
        GL11.glPopAttrib();
        grid = null;
        stroke = STROKE;
    }
}
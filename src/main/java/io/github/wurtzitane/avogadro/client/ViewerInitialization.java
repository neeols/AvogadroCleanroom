package io.github.wurtzitane.avogadro.client;

import io.github.wurtzitane.avogadro.Avogadro;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

/** Resolves first-use UI dependencies during loading, before an examiner is opened. */
final class ViewerInitialization {

    /** Private constructor to prevent instantiation of utility class. */
    private ViewerInitialization() {}

    /** Preloads and initializes viewer dependencies, UI classes, and matrix bridges asynchronously. */
    static void prepare() {
        GeometryPreparation.submit(() -> {
            ClassLoader loader = ViewerInitialization.class.getClassLoader();
            try {
                // Loading bytecode is safe here; GUI construction and GL stay on
                // the client thread. Only the two CPU-only initializers run below.
                for (String type : new String[]{
                        "com.cleanroommc.modularui.screen.ModularPanel",
                        "com.cleanroommc.modularui.screen.ModularScreen",
                        "com.cleanroommc.modularui.screen.RichTooltip",
                        "com.cleanroommc.modularui.widget.WidgetTree",
                        "com.cleanroommc.modularui.widget.InternalWidgetTree",
                        "com.cleanroommc.modularui.drawable.GuiDraw",
                        "com.cleanroommc.modularui.api.drawable.IKey",
                        MoleculeExaminer.class.getName()}) {
                    Class.forName(type, false, loader);
                }
                // MUI initializes its expression parser even for basic layout
                // arithmetic. Avoid paying that one-time cost in the first draw.
                Class.forName("com.cleanroommc.modularui.utils.MathUtils", true, loader);
                Class.forName(AtomicInspectionRenderer.class.getName(), true, loader);
            } catch (ReflectiveOperationException | LinkageError unavailable) {
                Avogadro.LOGGER.debug("Viewer initialization will finish on first use", unavailable);
            }
            return null;
        });
        if (Display.isCreated()) {
            // Resolve the native FloatBuffer matrix bridge without changing the
            // current matrix, matrix mode, textures, viewport, or other GL state.
            var identity = BufferUtils.createFloatBuffer(16);
            new Matrix4f().get(identity);
            GL11.glPushMatrix();
            try { GL11.glMultMatrixf(identity); }
            finally { GL11.glPopMatrix(); }
        }
    }
}
package io.github.wurtzitane.avogadro.client;

import static io.github.wurtzitane.avogadro.client.StructureStyle.*;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;

import io.github.wurtzitane.avogadro.molecule.MoleculeStructure;

import net.minecraft.client.gui.Gui;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;

/**
 * Shared viewport for finite chain and network examiners.
 *
 * <p>
 * Owns presentation state only: drag rotation, pan, zoom, pause timing, and zoom feedback. Subclasses own their
 * geometry and background preparation. Replacing geometry therefore leaves the camera and pause state intact.
 *
 * @param <T> concrete widget type, retained by ModularUI's fluent layout methods
 */
abstract class StructureViewport<T extends StructureViewport<T>> extends Widget<T>
        implements
            Interactable,
            AutoCloseable {
    /** Camera shared by all input and drawing paths for this viewport. */
    protected final MoleculeCamera camera;
    /** Animation time stops while paused, rather than jumping on resume. */
    private final MoleculeExaminer.AnimationClock clock = new MoleculeExaminer.AnimationClock(System.nanoTime());
    /** Brief zoom indicator; independent of the stored molecule geometry. */
    private final ExaminerPresentation.ZoomFeedback zoomFeedback = new ExaminerPresentation.ZoomFeedback();
    /** Selected pause state, also used by each screen's pause button. */
    boolean paused;

    /**
     * Creates the camera using the viewport's initial dimensions.
     *
     * @param width initial GUI width
     * @param height initial GUI height
     */
    StructureViewport(int width, int height) {
        camera = new MoleculeCamera(width, height);
    }

    /** Applies a completed preparation or notices a replaced definition before drawing. */
    protected abstract void updatePreview();
    /** @return last successful geometry, or null before the first successful preparation */
    protected abstract MoleculeStructure geometry();
    /** @return a short pending/error notice, or an empty string for an ordinary preview */
    protected abstract String status();
    /** Cancels preparation owned by the subclass; called on screen close and widget disposal. */
    protected abstract void closePreview();

    /** Resets rotation, pan, and zoom without changing parameters or geometry. */
    final void resetView() {
        camera.reset();
        zoomFeedback.clear();
    }
    /** Toggles rotation pause using the same clock as the other molecule examiners. */
    final void pause() {
        clock.toggle(System.nanoTime());
        paused = !paused;
    }
    /** @return pause-aware animation time in nanoseconds */
    private long now() {
        return clock.time(System.nanoTime());
    }
    /** @return configured automatic yaw at the current pause-aware time */
    private float yaw() {
        return MoleculeRenderer.rotationAngle(now() / 1_000_000_000d);
    }
    /** Ends an active camera drag before this viewport leaves the screen. */
    final void release() {
        if (camera.isDragging())
            camera.release(camera.draggingButton(), yaw(), now());
    }

    /** Releases interaction state and cancels owned preparation. Safe to call more than once. */
    @Override
    public final void close() {
        release();
        closePreview();
    }
    /** Releases preparation when ModularUI disposes this widget. */
    @Override
    public void dispose() {
        close();
        super.dispose();
    }

    /**
     * Draws the shared surface, complete geometry, zoom feedback, and preparation notice.
     *
     * @param context ModularUI's local widget coordinates and mouse state
     * @param theme active widget theme
     */
    @Override
    public final void draw(ModularGuiContext context, WidgetThemeEntry<?> theme) {
        updatePreview();
        int width = getArea().width, height = getArea().height;
        Gui.drawRect(0, 0, width, height, UiThemes.color("viewport.border", StructureStyle.VIEW_BORDER));
        Gui.drawRect(1, 1, width - 1, height - 1, UiThemes.color("viewport.background", StructureStyle.PAPER));
        camera.rendered();
        if (camera.isDragging()) {
            if (Mouse.isButtonDown(camera.draggingButton()) && Display.isActive())
                camera.drag(context.getMouseX(), context.getMouseY(), camera.draggingButton());
            else
                release();
        }
        long time = now();
        MoleculeStructure geometry = geometry();
        if (geometry != null)
            MoleculeRenderer.renderInViewport(geometry, 1, 1, width - 2, height - 2,
                    Math.max(1, Math.min(width - 16, height - 16)), camera.yaw(yaw(), time), camera.pitch(time),
                    camera.panX(), camera.panY(), camera.zoom());
        zoomFeedback.draw(camera.zoom(), width, height, StructureStyle.MUTED());
        String status = status();
        if (!status.isEmpty())
            text(status, 8, height - 16, width - 16, StructureStyle.MUTED());
    }

    /**
     * Starts the shared camera gesture; middle click resets it.
     *
     * @param button native mouse button
     * @return whether this viewport accepted the input
     */
    @Override
    public Result onMousePressed(int button) {
        if (button == 2) {
            resetView();
            return Result.SUCCESS;
        }
        return camera.start(getContext().getMouseX(), getContext().getMouseY(), button, yaw(), now())
                ? Result.SUCCESS
                : Result.IGNORE;
    }
    /**
     * Updates a camera drag using local widget coordinates.
     *
     * @param button native mouse button
     * @param elapsed drag duration supplied by ModularUI
     */
    @Override
    public void onMouseDrag(int button, long elapsed) {
        camera.drag(getContext().getMouseX(), getContext().getMouseY(), button);
    }
    /**
     * Ends a camera drag and resumes the configured automatic motion.
     *
     * @param button released button
     * @return whether the camera consumed the release
     */
    @Override
    public boolean onMouseRelease(int button) {
        return camera.release(button, yaw(), now());
    }
    /**
     * Zooms toward the cursor and refreshes the shared zoom feedback.
     *
     * @param direction wheel direction
     * @param amount wheel amount supplied by ModularUI
     * @return whether the camera handled this scroll
     */
    @Override
    public boolean onMouseScroll(UpOrDown direction, int amount) {
        float previous = camera.zoom();
        boolean handled = camera.scroll(direction.modifier, getContext().getMouseX() - getArea().width / 2f,
                getContext().getMouseY() - getArea().height / 2f);
        if (camera.zoom() != previous)
            zoomFeedback.changed(System.nanoTime());
        return handled;
    }
}

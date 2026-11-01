package io.github.wurtzitane.avogadro.client;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Manages recipe viewport camera rotation, zooming, panning, and frame tracking states.
 * <p>A recipe viewport camera; ordinary tooltips, HUDs and media exports keep their own orientation.
 */
public final class MoleculeCamera {

    /** Minimum zoom scale limit. */
    public static final float MIN_ZOOM = .25f;

    /** Maximum zoom scale limit. */
    public static final float MAX_ZOOM = 1_000_000f;

    /** Pan viewport factor limit. */
    private static final float PAN_VIEWPORTS = 4f;

    /**
     * Holds frame visibility and active camera state tracking data.
     */
    static final class FrameState {
        Set<MoleculeCamera> visible = identitySet();
        Set<MoleculeCamera> drawn = identitySet();
        MoleculeCamera active;
    }

    /** Global frame state instance tracking active and visible cameras. */
    static FrameState frame = new FrameState();

    private final MoleculeRotation rotation = new MoleculeRotation();
    private int viewportWidth;
    private int viewportHeight;
    private float panX;
    private float panY;
    private float zoom = 1f;
    private int draggingButton = -1;
    private int lastX;
    private int lastY;

    /**
     * Constructs a new {@link MoleculeCamera}.
     *
     * @param viewportWidth  viewport width in pixels
     * @param viewportHeight viewport height in pixels
     */
    public MoleculeCamera(int viewportWidth, int viewportHeight) {
        if (viewportWidth <= 0 || viewportHeight <= 0) {
            throw new IllegalArgumentException("A camera viewport must have positive dimensions.");
        }
        this.viewportWidth = viewportWidth;
        this.viewportHeight = viewportHeight;
    }

    /** Returns the current horizontal pan offset. */
    public float panX() {
        return panX;
    }

    /** Resizes the camera viewport dimensions. */
    void resizeViewport(int width, int height) {
        if (width <= 0 || height <= 0 || (width == viewportWidth && height == viewportHeight)) {
            return;
        }
        viewportWidth = width;
        viewportHeight = height;
        panX = clampPan(panX, width);
        panY = clampPan(panY, height);
    }

    /** Returns the current vertical pan offset. */
    public float panY() {
        return panY;
    }

    /** Returns the current zoom level scale. */
    public float zoom() {
        return zoom;
    }

    /** Returns the underlying molecule rotation controller. */
    public MoleculeRotation rotation() {
        return rotation;
    }

    /** Calculates the current yaw rotation angle. */
    public float yaw(float automaticYaw, long now) {
        return rotation.yaw(automaticYaw, now);
    }

    /** Calculates the current pitch rotation angle. */
    public float pitch(long now) {
        return rotation.pitch(now);
    }

    /** Checks whether the camera is currently being dragged. */
    public boolean isDragging() {
        return draggingButton >= 0;
    }

    /** Returns the mouse button currently being used for dragging. */
    public int draggingButton() {
        return draggingButton;
    }

    /**
     * Starts a mouse drag or rotation interaction.
     *
     * @param mouseX       current mouse x coordinate
     * @param mouseY       current mouse y coordinate
     * @param button       mouse button index
     * @param automaticYaw automatic yaw angle
     * @param now          current timestamp in nanoseconds
     * @return {@code true} if handled, {@code false} otherwise
     */
    public boolean start(int mouseX, int mouseY, int button, float automaticYaw, long now) {
        if (button != 0 && button != 1) {
            return false;
        }
        if (frame.active != null && frame.active != this) {
            frame.active.cancelCapture();
        }
        if (draggingButton >= 0) {
            release(draggingButton, automaticYaw, now);
        }
        draggingButton = button;
        lastX = mouseX;
        lastY = mouseY;
        if (button == 0) {
            rotation.start(mouseX, mouseY, automaticYaw, now);
        }
        frame.active = this;
        return true;
    }

    /** Updates mouse dragging transformations. */
    public boolean drag(int mouseX, int mouseY, int button) {
        if (button != draggingButton || button < 0) {
            return false;
        }
        if (button == 0) {
            rotation.drag(mouseX, mouseY);
        } else {
            panX = clampPan(panX + (long) mouseX - lastX, viewportWidth);
            panY = clampPan(panY + (long) mouseY - lastY, viewportHeight);
        }
        lastX = mouseX;
        lastY = mouseY;
        return true;
    }

    /** Releases active mouse interaction states. */
    public boolean release(int button, float automaticYaw, long now) {
        if (button != draggingButton || button < 0) {
            return false;
        }
        if (button == 0) {
            rotation.release(automaticYaw, now);
        }
        draggingButton = -1;
        if (frame.active == this) {
            frame.active = null;
        }
        return true;
    }

    /**
     * Zooms around the pointer's offset from the viewport center, retaining its inspected model position.
     * <p>Zoom around the pointer's offset from the viewport center, retaining its inspected model position.
     *
     * @param delta    scroll delta amount
     * @param pointerX mouse pointer x coordinate
     * @param pointerY mouse pointer y coordinate
     * @return {@code true} if handled, {@code false} otherwise
     */
    public boolean scroll(int delta, float pointerX, float pointerY) {
        if (delta == 0 || !Float.isFinite(pointerX) || !Float.isFinite(pointerY)) {
            return false;
        }
        double steps = Math.abs((long) delta) < 120 ? Math.signum(delta) : delta / 120.0;
        float changed = (float) Math.clamp(zoom * Math.pow(1.15, Math.clamp(steps, -8, 8)), MIN_ZOOM, MAX_ZOOM);
        float ratio = changed / zoom;
        zoom = changed;
        panX = clampPan(pointerX - (pointerX - panX) * ratio, viewportWidth);
        panY = clampPan(pointerY - (pointerY - panY) * ratio, viewportHeight);
        return true;
    }

    /** Resets camera pan, zoom, and rotation states. */
    public void reset() {
        cancelCapture();
        panX = panY = 0;
        zoom = 1;
    }

    /** Cancels active dragging and capture states. */
    private void cancelCapture() {
        rotation.reset();
        draggingButton = -1;
        if (frame.active == this) {
            frame.active = null;
        }
    }

    /** Registers that this camera was rendered during the current frame. */
    public void rendered() {
        frame.drawn.add(this);
        rotation.rendered();
    }

    /** Begins frame tracking for cameras. */
    public static void beginFrame() {
        frame.drawn.clear();
    }

    /** Ends frame tracking and resets invisible cameras. */
    public static void endFrame() {
        for (MoleculeCamera camera : frame.visible) {
            if (!frame.drawn.contains(camera)) {
                camera.reset();
            }
        }
        if (frame.active != null && !frame.drawn.contains(frame.active)) {
            frame.active.reset();
        }
        Set<MoleculeCamera> previous = frame.visible;
        frame.visible = frame.drawn;
        frame.drawn = previous;
        frame.drawn.clear();
    }

    /** Resets all tracked camera instances. */
    public static void resetAll() {
        for (MoleculeCamera camera : frame.visible) {
            camera.reset();
        }
        for (MoleculeCamera camera : frame.drawn) {
            camera.reset();
        }
        if (frame.active != null) {
            frame.active.reset();
        }
        frame.visible.clear();
        frame.drawn.clear();
    }

    /** Clamps pan translation values within allowable viewport boundaries. */
    private float clampPan(double value, int dimension) {
        double limit = dimension * PAN_VIEWPORTS * Math.max(1, zoom);
        return (float) Math.clamp(value, -limit, limit);
    }

    /** Creates a new identity hash set for camera collections. */
    private static Set<MoleculeCamera> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }
}
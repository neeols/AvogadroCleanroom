package io.github.wurtzitane.avogadro.client;

/** Mouse rotation belongs to a recipe entry; ordinary tooltips keep automatic rotation. */
public final class MoleculeRotation {

    /** Default vertical pitch rotation angle in degrees. */
    private static final float DEFAULT_PITCH = 15f;

    /** Scale factor mapping mouse pixel movement to rotation degrees. */
    private static final float DEGREES_PER_PIXEL = 2.5f;

    /**
     * Holds frame-tracking state for active mouse drag interactions.
     */
    static final class FrameState {
        MoleculeRotation active;
        boolean activeDrawn;
    }

    /** Shared static frame state tracking active rotations across render ticks. */
    static FrameState frame = new FrameState();

    /** Flag indicating whether the molecule is currently being actively dragged via mouse input. */
    private boolean dragging;

    /** Flag indicating whether the rotation is paused. */
    private boolean paused;

    /** Last recorded mouse X coordinate position during a drag interaction. */
    private int lastX;

    /** Last recorded mouse Y coordinate position during a drag interaction. */
    private int lastY;

    /** Current yaw rotation angle in degrees. */
    private float yaw;

    /** Current pitch rotation angle in degrees. */
    private float pitch = DEFAULT_PITCH;

    /** Offset between automatic yaw and manual drag yaw. */
    private float yawOffset;

    /**
     * Starts a mouse drag interaction for rotation.
     *
     * @param mouseX current mouse X coordinate
     * @param mouseY current mouse Y coordinate
     * @param automaticYaw current automatic yaw angle
     * @param now current timestamp
     */
    public void start(int mouseX, int mouseY, float automaticYaw, long now) {
        if (frame.active != null && frame.active != this)
            cancelActiveDrag();
        yaw = yaw(automaticYaw, now);
        pitch = pitch(now);
        dragging = true;
        lastX = mouseX;
        lastY = mouseY;
        frame.active = this;
    }

    /**
     * Updates rotation angles based on mouse movement during a drag.
     *
     * @param mouseX current mouse X coordinate
     * @param mouseY current mouse Y coordinate
     */
    public void drag(int mouseX, int mouseY) {
        if (!dragging)
            return;
        yaw = wrap(yaw + (mouseX - lastX) * DEGREES_PER_PIXEL);
        pitch = Math.clamp(pitch + (mouseY - lastY) * DEGREES_PER_PIXEL, -89f, 89f);
        lastX = mouseX;
        lastY = mouseY;
    }

    /**
     * Releases the active mouse drag interaction.
     *
     * @param automaticYaw current automatic yaw angle
     * @param now current timestamp
     */
    public void release(float automaticYaw, long now) {
        if (!dragging)
            return;
        dragging = false;
        yawOffset = wrap(yaw - automaticYaw);

        if (frame.active == this)
            frame.active = null;
    }

    /**
     * Computes the current yaw angle.
     *
     * @param automaticYaw current automatic yaw angle
     * @param now current timestamp
     * @return computed yaw angle in degrees
     */
    public float yaw(float automaticYaw, long now) {
        if (dragging || paused)
            return yaw;
        return automaticYaw + yawOffset;
    }

    /**
     * Returns the current pitch angle.
     *
     * @param now current timestamp
     * @return current pitch angle in degrees
     */
    public float pitch(long now) {
        return pitch;
    }

    /**
     * Checks whether a drag interaction is currently active.
     *
     * @return {@code true} if dragging, {@code false} otherwise
     */
    public boolean isDragging() {
        return dragging;
    }

    /**
     * Checks whether rotation is currently paused.
     *
     * @return {@code true} if paused, {@code false} otherwise
     */
    public boolean isPaused() {
        return paused;
    }

    /** Freeze the displayed pose; resume automatic spin from that exact pose. */
    public void togglePaused(float automaticYaw, long now) {
        if (paused) {
            paused = false;
            yawOffset = wrap(yaw - automaticYaw);

        } else {
            yaw = yaw(automaticYaw, now);
            pitch = pitch(now);
            paused = true;
        }
    }

    /** Resets rotation state to defaults. */
    public void reset() {
        dragging = false;
        paused = false;
        yawOffset = 0;
        pitch = DEFAULT_PITCH;
        if (frame.active == this)
            frame.active = null;
    }

    /** Cancels any active mouse drag rotation. */
    public static void cancelActiveDrag() {
        if (frame.active == null)
            return;
        frame.active.reset();
    }

    /** Initializes frame state at the start of a render frame. */
    public static void beginFrame() {
        frame.activeDrawn = false;
    }

    /** Marks the current rotation instance as rendered in this frame. */
    public void rendered() {
        if (frame.active == this)
            frame.activeDrawn = true;
    }

    /** Concludes the frame, cancelling active drags if they were not rendered. */
    public static void endFrame() {
        if (frame.active != null && !frame.activeDrawn)
            cancelActiveDrag();
    }

    /** Wraps an angle in degrees to the range [-180, 180). */
    private static float wrap(float degrees) {
        return (degrees % 360f + 540f) % 360f - 180f;
    }
}

package io.github.wurtzitane.avogadro.client;

import java.util.ArrayDeque;

/** Camera visibility belongs to each window's frames, including windows rendered less frequently. */
final class ViewerFrameScope {

    /** Frame state container for molecule cameras. */
    private final MoleculeCamera.FrameState cameras = new MoleculeCamera.FrameState();

    /** Frame state container for molecule rotations. */
    private final MoleculeRotation.FrameState rotations = new MoleculeRotation.FrameState();

    /**
     * Record holding previous frame states for camera and rotation scopes.
     *
     * @param cameras previous camera frame state
     * @param rotations previous rotation frame state
     */
    private record Previous(MoleculeCamera.FrameState cameras, MoleculeRotation.FrameState rotations) {
    }

    /** Deque storing stacked previous frame states. */
    private final ArrayDeque<Previous> previous = new ArrayDeque<>();

    /** Enters this frame scope by pushing current states and switching to scoped states. */
    void enter() {
        previous.push(new Previous(MoleculeCamera.frame, MoleculeRotation.frame));
        MoleculeCamera.frame = cameras;
        MoleculeRotation.frame = rotations;
    }

    /** Restores the previous frame states from the stack. */
    void restore() {
        if (previous.isEmpty())
            return;
        Previous saved = previous.pop();
        MoleculeCamera.frame = saved.cameras();
        MoleculeRotation.frame = saved.rotations();
    }

    /** Begins a new frame for rotations and cameras. */
    void beginFrame() {
        MoleculeRotation.beginFrame();
        MoleculeCamera.beginFrame();
    }

    /** Ends the current frame for rotations and cameras. */
    void endFrame() {
        MoleculeRotation.endFrame();
        MoleculeCamera.endFrame();
    }

    /** Disposes active drags and resets all cameras within this scope. */
    void dispose() {
        enter();
        try {
            MoleculeRotation.cancelActiveDrag();
            MoleculeCamera.resetAll();
        } finally {
            restore();
        }
    }
}

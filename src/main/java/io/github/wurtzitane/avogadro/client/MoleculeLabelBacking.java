package io.github.wurtzitane.avogadro.client;

import java.awt.geom.Rectangle2D;
import java.util.Arrays;

import io.github.wurtzitane.avogadro.molecule.MoleculeStructure;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Fades individual recipe-label backings when projected atoms or bonds pass behind them.
 * Uses the live renderer's transform, without GPU readbacks or a second geometry cache.
 */
public final class MoleculeLabelBacking {
    private final MoleculeStructure molecule;
    private final float[] x, y, radius;
    private final Matrix4f transform = new Matrix4f();
    private final Matrix4f model = new Matrix4f();
    private final Vector3f point = new Vector3f();
    private final Rectangle2D.Float bondBounds = new Rectangle2D.Float();
    private final float modelBound;
    private float[] opacity = new float[0];
    private float scale, elapsed;
    private boolean cubic;
    private boolean projected;
    private float boundX, boundY, boundRadius;
    private long previous = Long.MIN_VALUE;

    /**
     * Tracks overlap for one recipe card without changing its source geometry.
     * @param molecule immutable structure displayed by this card
     */
    public MoleculeLabelBacking(MoleculeStructure molecule) {
        this.molecule = molecule;
        x = new float[molecule.atoms().size()];
        y = new float[x.length];
        radius = new float[x.length];
        float largest = 0;
        for (var atom : molecule.atoms()) largest = Math.max(largest, atom.element().radius());
        modelBound = molecule.radius() + largest * .56f + .54f;
    }

    /**
     * Prepares the frame's projection; individual atoms are projected only if a label is near the model.
     * Coordinates and camera values must match the rendered viewport.
     * A hidden card starts with clear labels when it returns, rather than retaining an old backing.
     *
     * @param left initial viewport left
     * @param top initial viewport top
     * @param width initial viewport width
     * @param height initial viewport height
     * @param size fitted image size
     * @param yaw camera yaw in degrees
     * @param pitch camera pitch in degrees
     * @param panX horizontal pan
     * @param panY vertical pan
     * @param zoom camera zoom
     * @param cubes whether atoms are cubes
     * @param now monotonic frame time in nanoseconds
     */
    public void prepare(int left, int top, int width, int height, int size, float yaw, float pitch,
            float panX, float panY, float zoom, boolean cubes, long now) {
        long delta = previous == Long.MIN_VALUE ? 0 : Math.max(0, now - previous);
        if (delta > 500_000_000L) {
            Arrays.fill(opacity, 0);
            delta = 0;
        }
        previous = now;
        elapsed = Math.min(delta, 100_000_000L) / 1_000_000_000f;
        cubic = cubes;
        float centerX = left + width / 2f, centerY = top + height / 2f;
        transform.translation(centerX + panX, centerY + panY, 0).scale(zoom, zoom, 1)
                .translate(-centerX, -centerY, 0)
                .mul(MoleculeRenderer.transform(model, molecule, left + (width - size) / 2,
                        top + (height - size) / 2, size, yaw, pitch));
        scale = size * .45f * zoom / (molecule.radius() > .001f ? molecule.radius() : 1f);
        transform.transformPosition(point.set(molecule.centerX(), molecule.centerY(), molecule.centerZ()));
        boundX = point.x;
        boundY = point.y;
        boundRadius = modelBound * scale;
        projected = false;
    }

    /** Most default views cannot touch the labels; only project individual atoms when needed. */
    private void projectAtoms() {
        if (projected) return;
        projected = true;
        for (int i = 0; i < x.length; i++) {
            var atom = molecule.atoms().get(i);
            transform.transformPosition(point.set(atom.x(), atom.y(), atom.z()));
            x[i] = point.x;
            y[i] = point.y;
            radius[i] = atom.element().radius() * .9f * scale * (cubic ? (float) Math.sqrt(3) : 1);
        }
    }

    /**
     * Returns a fade from zero to one for one label. Call once per label per prepared frame.
     * Each label responds independently, including a formula whose name remains unobscured.
     *
     * @param label stable zero-based label number
     * @param left text bounds left
     * @param top text bounds top
     * @param right text bounds right
     * @param bottom text bounds bottom
     * @return backing opacity multiplier
     */
    public float opacity(int label, float left, float top, float right, float bottom) {
        if (label >= opacity.length)
            opacity = Arrays.copyOf(opacity, label + 1);
        boolean overlap = overlaps(left, top, right, bottom);
        opacity[label] = overlap ? Math.min(1, opacity[label] + elapsed / .16f)
                : Math.max(0, opacity[label] - elapsed / .24f);
        return opacity[label];
    }

    private boolean overlaps(float left, float top, float right, float bottom) {
        float dxBound = boundX - Math.max(left, Math.min(boundX, right));
        float dyBound = boundY - Math.max(top, Math.min(boundY, bottom));
        if (dxBound * dxBound + dyBound * dyBound > boundRadius * boundRadius)
            return false;
        projectAtoms();
        for (int i = 0; i < x.length; i++) {
            float dx = x[i] - Math.max(left, Math.min(x[i], right));
            float dy = y[i] - Math.max(top, Math.min(y[i], bottom));
            if (dx * dx + dy * dy <= radius[i] * radius[i])
                return true;
        }
        for (var bond : molecule.bonds()) {
            int a = bond.from(), b = bond.to();
            float extent = switch (bond.type().order()) {
                case 1 -> .15f;
                case 2 -> .24f;
                default -> .38f;
            } * scale * (cubic ? (float) Math.sqrt(2) : 1);
            bondBounds.setRect(left - extent, top - extent, right - left + extent * 2, bottom - top + extent * 2);
            if (bondBounds.intersectsLine(x[a], y[a], x[b], y[b]))
                return true;
        }
        return false;
    }
}

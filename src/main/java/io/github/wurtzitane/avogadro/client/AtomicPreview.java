package io.github.wurtzitane.avogadro.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import io.github.wurtzitane.avogadro.api.MoleculeClassification;
import io.github.wurtzitane.avogadro.molecule.MoleculeDef;

import org.joml.Matrix4f;

/**
 * Bounded compact views of the same atomic model used by the examiner. Particle preparation runs on the
 * geometry worker; GPU resources belong to the client thread and are released on eviction or resource reload.
 */
public final class AtomicPreview {
    private static final int MAX_PREVIEWS = 16;
    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static final Matrix4f POSE = new Matrix4f();
    private static final Matrix4f MODEL = new Matrix4f();
    private static long clock;

    private AtomicPreview() {}

    /**
     * Only a single real element atom has a one-to-one atomic preview. Elemental molecules such as oxygen
     * and fullerene keep their authored molecular structure, as do crystals, polymers, and unresolved groups.
     * @param definition definition to display
     * @return whether its compact model can be replaced with an atomic view
     */
    public static boolean supports(MoleculeDef definition) {
        return definition != null && definition.structure().atoms().size() == 1
                && MoleculeClassification.of(definition) == MoleculeClassification.ELEMENT
                && InspectionModelData.canInspect(definition.structure(), 0);
    }

    /** Prepare a hovered atom ahead of a possible tooltip modifier press. */
    static void warm(MoleculeDef definition) {
        if (supports(definition)) entry(definition);
    }

    /**
     * Renders inside the card's outer clip, using its existing molecular fit and camera.
     * @param definition single-atom definition
     * @param left initial model viewport left
     * @param top initial model viewport top
     * @param width initial model viewport width
     * @param height initial model viewport height
     * @param size fitted molecular image size
     * @param yaw current yaw in degrees
     * @param pitch current pitch in degrees
     * @param panX camera horizontal pan
     * @param panY camera vertical pan
     * @param zoom camera zoom
     * @param clipLeft outer clip left
     * @param clipTop outer clip top
     * @param clipRight outer clip right
     * @param clipBottom outer clip bottom
     * @param animationNanos continuous particle animation time
     * @return false while preparation is pending, so the caller can retain its solid preview
     */
    public static boolean draw(MoleculeDef definition, int left, int top, int width, int height, int size,
            float yaw, float pitch, float panX, float panY, float zoom, int clipLeft, int clipTop,
            int clipRight, int clipBottom, long animationNanos) {
        if (!supports(definition) || size <= 0 || width <= 0 || height <= 0 || clipRight <= clipLeft || clipBottom <= clipTop
                || !Float.isFinite(yaw) || !Float.isFinite(pitch) || !Float.isFinite(panX) || !Float.isFinite(panY)
                || !Float.isFinite(zoom) || zoom <= 0) return false;
        Entry entry = entry(definition);
        if (entry.renderer == null) entry.renderer = entry.preparation.take();
        if (entry.renderer == null) return false;
        pose(POSE, MODEL, definition, left, top, width, height, size, yaw, pitch, panX, panY, zoom,
                entry.renderer.previewRadiusRatio());
        try (var ignored = UiThemes.element("atom-preview")) {
            entry.renderer.renderPreview(POSE, clipLeft, clipTop, clipRight, clipBottom, animationNanos, yaw, pitch);
        }
        return true;
    }

    /** The cloud's radius replaces the old 0.9-radius sphere at exactly the same transformed center. */
    static Matrix4f pose(Matrix4f result, Matrix4f model, MoleculeDef definition, int left, int top,
            int width, int height, int size, float yaw, float pitch, float panX, float panY, float zoom,
            float cloudRadiusRatio) {
        float pivotX = left + width / 2f, pivotY = top + height / 2f;
        var atom = definition.structure().atoms().getFirst();
        MoleculeRenderer.transform(model, definition.structure(), left + (width - size) / 2,
                top + (height - size) / 2, size, yaw, pitch);
        model.translate(atom.x(), atom.y(), atom.z()).scale(.9f / cloudRadiusRatio)
                .translate(-atom.x(), -atom.y(), -atom.z());
        return result.translation(pivotX + panX, pivotY + panY, 0).scale(zoom, zoom, 1)
                .translate(-pivotX, -pivotY, 0).mul(model);
    }

    private static Entry entry(MoleculeDef definition) {
        for (Entry entry : ENTRIES) if (entry.definition == definition) {
            entry.used = ++clock;
            return entry;
        }
        if (ENTRIES.size() == MAX_PREVIEWS) {
            Entry oldest = ENTRIES.stream().min(Comparator.comparingLong(e -> e.used)).orElseThrow();
            oldest.close();
            ENTRIES.remove(oldest);
        }
        Entry entry = new Entry(definition);
        ENTRIES.add(entry);
        return entry;
    }

    /** Release retained previews on the client thread, before resource or GL-context teardown. */
    public static void clear() {
        ENTRIES.forEach(Entry::close);
        ENTRIES.clear();
    }

    private static final class Entry implements AutoCloseable {
        final MoleculeDef definition;
        final PreviewPreparation<AtomicInspectionRenderer> preparation = new PreviewPreparation<>();
        AtomicInspectionRenderer renderer;
        long used = ++clock;

        Entry(MoleculeDef definition) {
            this.definition = definition;
            preparation.request(() -> {
                var prepared = new AtomicInspectionRenderer(definition);
                prepared.preparePreview();
                return prepared;
            });
        }

        @Override public void close() {
            preparation.close();
            if (renderer != null) renderer.close();
        }
    }
}

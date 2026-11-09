package io.github.wurtzitane.avogadro.client;

import java.util.Map;
import java.util.WeakHashMap;

import io.github.wurtzitane.avogadro.api.CrystalCell;
import io.github.wurtzitane.avogadro.molecule.MoleculeDef;

/**
 * Renders cached crystal previews for molecule definitions.
 * <p>
 * Render-thread cache; immutable definitions release their outline when their snapshot is discarded.
 */
final class CrystalPreview {

    /**
     * Represents a cached crystal preview comprising an outline and a fit scale factor.
     *
     * @param outline crystal outline instance
     * @param fit fit scale factor
     */
    private record Preview(CrystalOutline outline, float fit) {
    }

    /** Weak map caching crystal previews per molecule definition. */
    private static final Map<MoleculeDef, Preview> CACHE = new WeakHashMap<>();

    private CrystalPreview() {}

    /**
     * Draws a preview of the crystal structure for the specified molecule definition.
     *
     * @param definition molecule definition
     * @param x x coordinate position
     * @param y y coordinate position
     * @param size display size dimension in pixels
     * @param angle rotation angle
     */
    static void draw(MoleculeDef definition, int x, int y, int size, float angle) {
        Preview preview = CACHE.computeIfAbsent(definition, value -> {
            CrystalCell cell = CrystalCell.of(value);
            var structure = value.structure();
            var center = structure.center();
            float bound = structure.radius();
            for (int i = 0; i <= 1; i++) {
                for (int j = 0; j <= 1; j++) {
                    for (int k = 0; k <= 1; k++) {
                        bound = Math.max(bound, cell.position(i, j, k).distance(center));
                    }
                }
            }
            return new Preview(new CrystalOutline(cell, 1), structure.radius() / bound);
        });
        MoleculeRenderer.renderCrystal(definition.structure(), x, y, size, size,
                Math.max(1, (int) (size * preview.fit())), angle, 15, 0, 0, 1, preview.outline());
    }
}

package io.github.wurtzitane.avogadro.client;

import java.util.ArrayList;
import java.util.List;

import io.github.wurtzitane.avogadro.api.AtomicConfiguration;

/** Deterministic particle positions; no graphics context or chemistry catalog is required. */
public final class NuclearGeometry {

    private NuclearGeometry() {}

    /**
     * Record representing an individual nucleon particle position and type.
     *
     * @param x x coordinate position
     * @param y y coordinate position
     * @param z z coordinate position
     * @param proton whether the nucleon is a proton ({@code true}) or neutron ({@code false})
     */
    public record Nucleon(float x, float y, float z, boolean proton) {
    }

    /**
     * Computes the list of nucleons for a given atomic configuration.
     *
     * @param configuration atomic configuration
     * @return list of positioned nucleons
     */
    public static List<Nucleon> nucleus(AtomicConfiguration configuration) {
        return packing(configuration).nucleons();
    }

    /**
     * Record holding packed nuclear layout details, including nucleons, particle radius, and overall nuclear radius.
     *
     * @param nucleons list of nucleons
     * @param nucleonRadius individual nucleon particle radius
     * @param radius overall nuclear radius
     */
    record Packing(List<Nucleon> nucleons, float nucleonRadius, float radius) {
    }

    /**
     * Calculates closest-packed lattice sites for a nucleus based on mass number and atomic number.
     *
     * @param configuration atomic configuration
     * @return packing details containing positioned nucleons and sizing
     */
    static Packing packing(AtomicConfiguration configuration) {
        int count = configuration.massNumber();
        List<Nucleon> result = new ArrayList<>(count);
        // Closest-packed lattice sites fill a ball. A golden-angle spiral correlates latitude
        // and azimuth and can produce a crescent, so it is not used for filled nuclei.
        record Site(int x, int y, int z) {
            int radius() {
                return x * x + y * y + z * z;
            }
            long order() {
                long h = x * 73856093L ^ y * 19349663L ^ z * 83492791L;
                return h ^ (h >>> 17);
            }
        }
        List<Site> candidates = new ArrayList<>();
        int bound = 1;
        do {
            candidates.clear();
            for (int x = -bound; x <= bound; x++)
                for (int y = -bound; y <= bound; y++)
                    for (int z = -bound; z <= bound; z++)
                        if (((x + y + z) & 1) == 0)
                            candidates.add(new Site(x, y, z));
            bound++;
        } while (candidates.size() < count * 3);
        candidates.sort(java.util.Comparator.comparingInt(Site::radius).thenComparingLong(Site::order));
        var chosen = candidates.subList(0, count);
        double cx = 0, cy = 0, cz = 0;
        for (var site : chosen) {
            cx += site.x();
            cy += site.y();
            cz += site.z();
        }
        cx /= count;
        cy /= count;
        cz /= count;
        double radius = 0;
        for (var site : chosen)
            radius = Math.max(radius,
                    Math.sqrt(Math.pow(site.x() - cx, 2) + Math.pow(site.y() - cy, 2) + Math.pow(site.z() - cz, 2)));
        // Size the sites and particles together. The old independent size formula left
        // holes in light nuclei. This overlap also covers the particle mesh's flat faces.
        double scale = radius == 0 ? 0 : .29 / (radius + 1.25);
        float nucleonRadius = radius == 0 ? .115f : (float) (scale * 1.25);
        for (int i = 0; i < count; i++) {
            var site = chosen.get(i);
            boolean proton = (long) (i + 1) * configuration.atomicNumber() / count > (long) i
                    * configuration.atomicNumber() / count;
            result.add(new Nucleon((float) ((site.x() - cx) * scale), (float) ((site.y() - cy) * scale),
                    (float) ((site.z() - cz) * scale), proton));
        }
        return new Packing(List.copyOf(result), nucleonRadius, (float) (radius * scale) + nucleonRadius);
    }
}

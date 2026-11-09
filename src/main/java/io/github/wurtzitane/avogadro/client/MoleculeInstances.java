package io.github.wurtzitane.avogadro.client;

import io.github.wurtzitane.avogadro.molecule.MoleculeStructure;

/**
 * Manages model-space instance buffers for atoms and bonds. Camera transformations and rotations never enter these
 * buffers.
 */
final class MoleculeInstances {

    /** Stride size for an atom in the instance buffer. */
    static final int ATOM_STRIDE = 7;

    /** Stride size for a bond in the instance buffer. */
    static final int BOND_STRIDE = 13;

    /** Float buffer storing atom instance data. */
    final float[] atoms;

    /** Float buffer storing bond instance data. */
    final float[] bonds;

    /**
     * Constructs molecule instances using the default styling.
     *
     * @param molecule the molecule structure
     * @param palette the element color palette snapshot
     */
    MoleculeInstances(MoleculeStructure molecule, ElementPalette.Snapshot palette) {
        this(molecule, palette, MoleculeStyle.DEFAULT);
    }

    /**
     * Constructs molecule instances using a custom molecule style.
     *
     * @param molecule the molecule structure
     * @param palette the element color palette snapshot
     * @param style the molecule display style configuration
     */
    MoleculeInstances(MoleculeStructure molecule, ElementPalette.Snapshot palette, MoleculeStyle style) {
        atoms = new float[molecule.atoms().size() * ATOM_STRIDE];
        int index = 0;
        for (var atom : molecule.atoms()) {
            GeometryPreparation.checkCancelled();
            int color = palette.color(atom.element().symbol(), atom.element().color());
            atoms[index++] = atom.x();
            atoms[index++] = atom.y();
            atoms[index++] = atom.z();
            atoms[index++] = atom.element().radius() * 0.9f * style.atoms();

            atoms[index++] = (color >> 16 & 255) / 255f;
            atoms[index++] = (color >> 8 & 255) / 255f;
            atoms[index++] = (color & 255) / 255f;
        }

        int cylinders = 0;
        for (var bond : molecule.bonds()) {
            GeometryPreparation.checkCancelled();
            var a = molecule.atoms().get(bond.from());
            var b = molecule.atoms().get(bond.to());
            float dx = b.x() - a.x(), dy = b.y() - a.y(), dz = b.z() - a.z();
            if ((float) Math.sqrt(dx * dx + dy * dy + dz * dz) >= 0.0001f) {
                cylinders += bond.type().order() <= 1 ? 1 : bond.type().order() == 2 ? 2 : 3;
            }
        }

        float[] lines = new float[cylinders * BOND_STRIDE];
        index = 0;
        for (var bond : molecule.bonds()) {
            GeometryPreparation.checkCancelled();
            var a = molecule.atoms().get(bond.from());
            var b = molecule.atoms().get(bond.to());
            float dx = b.x() - a.x(), dy = b.y() - a.y(), dz = b.z() - a.z();
            int order = bond.type().order();
            if (order <= 1) {
                index = line(lines, index, a.x(), a.y(), a.z(), b.x(), b.y(), b.z(), 0.15f * style.bonds());
                continue;
            }
            if (dx * dx + dy * dy + dz * dz < 0.00000001f)
                continue;

            float px, py, pz;
            if (Math.abs(dx) <= Math.abs(dy) && Math.abs(dx) <= Math.abs(dz)) {
                float inv = 1f / (float) Math.sqrt(dz * dz + dy * dy);
                px = 0;
                py = dz * inv;
                pz = -dy * inv;
            } else if (Math.abs(dy) <= Math.abs(dz)) {
                float inv = 1f / (float) Math.sqrt(dz * dz + dx * dx);
                px = dz * inv;
                py = 0;
                pz = -dx * inv;
            } else {
                float inv = 1f / (float) Math.sqrt(dy * dy + dx * dx);
                px = -dy * inv;
                py = dx * inv;
                pz = 0;
            }

            if (order != 2) {
                index = line(lines, index, a.x(), a.y(), a.z(), b.x(), b.y(), b.z(), 0.10f * style.bonds());
            }

            float ox = px * 0.14f * style.bonds(), oy = py * 0.14f * style.bonds(), oz = pz * 0.14f * style.bonds();
            if (order != 2) {
                ox *= 2;
                oy *= 2;
                oz *= 2;
            }

            index = line(lines, index, a.x() + ox, a.y() + oy, a.z() + oz, b.x() + ox, b.y() + oy, b.z() + oz,
                    0.10f * style.bonds());
            index = line(lines, index, a.x() - ox, a.y() - oy, a.z() - oz, b.x() - ox, b.y() - oy, b.z() - oz,
                    0.10f * style.bonds());
        }

        // Offset arithmetic can round a very short multiple bond to zero length.
        // Retain the old degenerate-cylinder behavior without speculative over-allocation.
        bonds = index == lines.length ? lines : java.util.Arrays.copyOf(lines, index);
    }

    /**
     * Appends cylinder data for a single bond line into the output float buffer.
     *
     * @param out the destination float array buffer
     * @param i the current buffer index pointer
     * @param fx from x coordinate
     * @param fy from y coordinate
     * @param fz from z coordinate
     * @param tx to x coordinate
     * @param ty to y coordinate
     * @param tz to z coordinate
     * @param radius the cylinder radius
     * @return the updated index pointer
     */
    private static int line(float[] out, int i, float fx, float fy, float fz, float tx, float ty, float tz,
            float radius) {
        float dx = tx - fx, dy = ty - fy, dz = tz - fz;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 0.0001f)
            return i;
        float nx = dx / len, ny = dy / len, nz = dz / len;
        float ax = Math.abs(nx), ay = Math.abs(ny), az = Math.abs(nz);
        float refx = 0, refy = 0, refz = 0;
        if (ax <= ay && ax <= az)
            refx = 1;
        else if (ay <= az)
            refy = 1;
        else
            refz = 1;
        float ux = ny * refz - nz * refy, uy = nz * refx - nx * refz, uz = nx * refy - ny * refx;
        float ul = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
        ux /= ul;
        uy /= ul;
        uz /= ul;

        out[i++] = fx;
        out[i++] = fy;
        out[i++] = fz;
        out[i++] = radius;
        out[i++] = tx;
        out[i++] = ty;
        out[i++] = tz;
        out[i++] = ny * uz - nz * uy;
        out[i++] = nz * ux - nx * uz;
        out[i++] = nx * uy - ny * ux;
        out[i++] = ux;
        out[i++] = uy;
        out[i++] = uz;
        return i;
    }
}

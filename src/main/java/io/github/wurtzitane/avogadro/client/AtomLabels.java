package io.github.wurtzitane.avogadro.client;

import io.github.wurtzitane.avogadro.molecule.MoleculeStructure;
import net.minecraft.client.renderer.GlStateManager;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

/** Screen-facing external-group labels and authored radical dots on the complete 3D model. */
final class AtomLabels {
    /** Reused render-thread projection scratch; ordinary atoms never enter this path. */
    private static final Vector3f POSITION = new Vector3f();
    /** Fixed strings avoid allocating dot sequences each animation frame. */
    private static final String[] DOTS = {"", "•", "••", "•••", "••••"};
    private AtomLabels() {}

    /**
     * Draws only preselected annotations using the active resource-pack font.
     * @param molecule complete model with load-time annotation subset
     * @param transform exact geometry transform, including export/camera scaling
     */
    static void draw(MoleculeStructure molecule, Matrix4f transform) {
        var atoms = molecule.annotatedAtoms();
        if (atoms.isEmpty()) return;
        var font = PixelFont.renderer();
        int texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glColor4f(1, 1, 1, 1);
            for (int i = 0; i < atoms.size(); i++) {
                var atom = atoms.get(i);
                transform.transformPosition(POSITION.set(atom.x(), atom.y(), atom.z()));
                String label = atom.annotation().label();
                boolean radical = label.isEmpty();
                if (radical) label = DOTS[atom.annotation().radicalElectrons()];
                float x = POSITION.x - font.getStringWidth(label) / 2f;
                float y = POSITION.y - font.FONT_HEIGHT / 2f;
                // Center the marker on the same projected atom position as an external-group label.
                try (var ignored = UiThemes.element("atom-annotation/" + i)) {
                    font.drawString(label, x, y, UiThemes.color(radical ? "atom.radical" : "atom.label", 0xFFFFFFFF), true);
                }
            }
        } finally {
            // Keep Minecraft's texture cache consistent with the attribute restoration.
            GlStateManager.bindTexture(0);
            GlStateManager.bindTexture(texture);
            GL11.glPopAttrib();
        }
    }
}

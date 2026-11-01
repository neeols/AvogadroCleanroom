package io.github.wurtzitane.avogadro.client;

import net.minecraft.client.renderer.tileentity.TileEntityItemStackRenderer;
import net.minecraft.item.ItemStack;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

/**
 * Handles tile entity item stack rendering for periodic table items displayed in inventories or held in hand.
 */
public final class PeriodicTableItemRenderer extends TileEntityItemStackRenderer {

    /**
     * Renders the periodic table item stack using specialized graphics contexts and artwork boards.
     *
     * @param stack        item stack being rendered
     * @param partialTicks partial tick time interpolation factor
     */
    @Override public void renderByItem(ItemStack stack, float partialTicks) {
        try (var ignored = new PeriodicTableGraphics()) {
            GL20.glUseProgram(0);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(true);
            GL11.glTranslatef(.5f, .5f, .5f);
            PeriodicTableArtwork.drawBoard(.96f, .7f, false);
        }
    }
}
package io.github.wurtzitane.avogadro.client;

import io.github.wurtzitane.avogadro.entity.PeriodicTableEntity;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

import javax.annotation.Nullable;

/**
 * Handles client-side rendering for periodic table entities in the 3D world.
 */
public final class PeriodicTableEntityRenderer extends Render<PeriodicTableEntity> {

    /**
     * Constructs a new periodic table entity renderer.
     *
     * @param manager render manager instance
     */
    public PeriodicTableEntityRenderer(RenderManager manager) { super(manager); }

    /**
     * Renders the periodic table entity at the specified world coordinates.
     *
     * @param entity       periodic table entity
     * @param x            x coordinate position
     * @param y            y coordinate position
     * @param z            z coordinate position
     * @param yaw          entity rotation yaw
     * @param partialTicks partial tick time interpolation factor
     */
    @Override public void doRender(PeriodicTableEntity entity, double x, double y, double z,
                                   float yaw, float partialTicks) {
        try (var ignored = new PeriodicTableGraphics()) {
            GL20.glUseProgram(0);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(true);
            PeriodicTableGraphics.activeTexture(GL13.GL_TEXTURE1);
            if (io.github.wurtzitane.avogadro.Config.PERIODIC_TABLE_GLOWING.get())
                GL11.glDisable(GL11.GL_TEXTURE_2D);
            else GL11.glEnable(GL11.GL_TEXTURE_2D);
            // Sample the exposed side of the hanging entity, as vanilla paintings do.
            int light = entity.world.getCombinedLight(new net.minecraft.util.math.BlockPos(entity), 0);
            GL13.glMultiTexCoord2f(GL13.GL_TEXTURE1, light & 65535, light >>> 16);
            PeriodicTableGraphics.activeTexture(GL13.GL_TEXTURE0);
            GL11.glTranslated(x, y, z);
            GL11.glRotatef(180 - yaw, 0, 1, 0);
            PeriodicTableArtwork.drawBoard(entity.widthBlocks(), entity.heightBlocks(), true);
            PeriodicTableArtwork.drawHover(entity.widthBlocks(), entity.heightBlocks(),
                    PeriodicTableTarget.element(entity, partialTicks));
        }
        super.doRender(entity, x, y, z, yaw, partialTicks);
    }

    /**
     * Retrieves the entity texture resource location.
     *
     * @param entity periodic table entity
     * @return null resource location
     */
    @Nullable @Override protected ResourceLocation getEntityTexture(PeriodicTableEntity entity) { return null; }
}
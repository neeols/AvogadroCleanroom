package io.github.wurtzitane.avogadro.client;

import io.github.wurtzitane.avogadro.entity.PeriodicTableEntity;
import net.minecraft.client.Minecraft;

/** Ray selection uses the same fitted artwork and cells as the wall overlay and GUI. */
public final class PeriodicTableTarget {

    /** Private constructor to prevent instantiation of utility class. */
    private PeriodicTableTarget() {}

    /**
     * Generates a display label string for the element currently targeted by ray tracing on the table entity.
     *
     * @param table periodic table entity
     * @return element label string, or {@code null} if no element is targeted
     */
    public static String label(PeriodicTableEntity table) {
        return label(element(table, Minecraft.getMinecraft().getRenderPartialTicks()));
    }

    /**
     * Generates a display label string for an element by its atomic number.
     *
     * @param number atomic number
     * @return formatted element label string (name and symbol), or {@code null} if invalid
     */
    static String label(int number) {
        if (number < 0) return null;
        var element = io.github.wurtzitane.avogadro.api.ElementCatalog.byAtomicNumber(number);
        return element == null ? null : element.name() + " (" + element.symbol() + ")";
    }

    /**
     * Performs ray tracing against the periodic table entity to determine which element cell is targeted.
     *
     * @param table        periodic table entity
     * @param partialTicks partial tick time interpolation factor
     * @return atomic number of the targeted element, or {@code -1} if none
     */
    static int element(PeriodicTableEntity table, float partialTicks) {
        var client = Minecraft.getMinecraft();
        if (client.currentScreen != null || client.getRenderViewEntity() == null) return -1;

        var target = client.objectMouseOver;
        if (target == null || target.entityHit != table) return -1;

        // Ensure artwork and layout are initialized
        PeriodicTableArtwork.texture();
        var layout = PeriodicTableArtwork.layout();
        if (layout == null) return -1;

        var viewer = client.getRenderViewEntity();
        var facing = table.getHorizontalFacing();
        var right = facing.rotateYCCW();
        var eye = viewer.getPositionEyes(partialTicks);
        var direction = viewer.getLook(partialTicks);
        double normalX = facing.getXOffset(), normalZ = facing.getZOffset();
        double approach = direction.x * normalX + direction.z * normalZ;
        if (approach >= -1e-7) return -1; // No selection through the back of the board.
        double planeX = table.posX + normalX * .033;
        double planeZ = table.posZ + normalZ * .033;
        double distance = ((planeX - eye.x) * normalX + (planeZ - eye.z) * normalZ) / approach;
        if (distance < 0 || distance > client.playerController.getBlockReachDistance()) return -1;

        double dx = eye.x + direction.x * distance - table.posX;
        double dy = eye.y + direction.y * distance - table.posY;
        double dz = eye.z + direction.z * distance - table.posZ;
        float width = PeriodicTableArtwork.fittedWidth(table.widthBlocks(), table.heightBlocks());
        float height = width / PeriodicTableArtwork.aspect();
        float u = (float) (.5 + (dx * right.getXOffset() + dz * right.getZOffset()) / width);
        float v = (float) (.5 - dy / height);

        return layout.hit(u * layout.width(), v * layout.height());
    }
}
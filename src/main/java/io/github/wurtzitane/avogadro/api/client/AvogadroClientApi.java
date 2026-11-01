package io.github.wurtzitane.avogadro.api.client;

import io.github.wurtzitane.avogadro.api.MoleculeView;
import io.github.wurtzitane.avogadro.client.MoleculeRenderer;
import io.github.wurtzitane.avogadro.client.MoleculeTooltips;
import io.github.wurtzitane.avogadro.molecule.MoleculeStore;
import io.github.wurtzitane.avogadro.molecule.MoleculeStructure;
import net.minecraftforge.fluids.FluidStack;

/**
 * Explicit client entry point for the Avogadro API.
 *
 * <p><b>Warning:</b> Never reference this class from common-side initialization.
 */
public final class AvogadroClientApi {

    /**
     * Private constructor to prevent instantiation of this utility/API class.
     */
    private AvogadroClientApi() {}

    /**
     * Retrieves the current molecule view.
     *
     * @return the current {@link MoleculeView} instance
     */
    public static MoleculeView view() {
        return MoleculeStore.view();
    }

    /**
     * Renders a molecule structure at the specified screen coordinates with a fixed size and angle.
     *
     * @param structure the molecule structure to render
     * @param x         the x-coordinate on the screen
     * @param y         the y-coordinate on the screen
     * @param size      the render size (width and height)
     * @param angle     the rotation angle
     */
    public static void render(MoleculeStructure structure, int x, int y, int size, float angle) {
        MoleculeRenderer.render(structure, x, y, size, angle);
    }

    /**
     * Gets the current rotation angle used for animations or rendering.
     *
     * @return the current rotation angle
     */
    public static float rotationAngle() {
        return MoleculeRenderer.rotationAngle();
    }

    /**
     * Renders a molecule structure with explicit dimensions, options, and reproducible timing.
     *
     * <p><b>Render-thread only.</b> The {@code elapsedSeconds} parameter is caller-owned,
     * which makes animations fully reproducible.
     *
     * @param structure      the molecule structure to render
     * @param x              the x-coordinate on the screen
     * @param y              the y-coordinate on the screen
     * @param width          the render width
     * @param height         the render height
     * @param options        the rendering options to apply
     * @param elapsedSeconds caller-owned elapsed seconds for reproducible animation
     */
    public static void render(MoleculeStructure structure, int x, int y, int width, int height,
                              MoleculeRenderOptions options, double elapsedSeconds) {
        MoleculeRenderer.render(structure, x, y, width, height, options, elapsedSeconds);
    }

    /**
     * Retrieves the media export API instance.
     *
     * @return the {@link MediaExportApi} instance
     */
    public static MediaExportApi exports() {
        return MediaExportApi.INSTANCE;
    }

    /**
     * Executes drawing logic within a fluid tooltip context.
     *
     * @param fluid the fluid stack to display details for
     * @param draw  the runnable containing the drawing logic
     */
    public static void withFluidTooltip(FluidStack fluid, Runnable draw) {
        MoleculeTooltips.withFluidTooltip(fluid, draw);
    }
}
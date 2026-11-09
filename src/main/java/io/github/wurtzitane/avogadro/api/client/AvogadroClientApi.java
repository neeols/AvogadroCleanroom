package io.github.wurtzitane.avogadro.api.client;

import io.github.wurtzitane.avogadro.api.MoleculeView;
import io.github.wurtzitane.avogadro.client.MoleculeRenderer;
import io.github.wurtzitane.avogadro.client.MoleculeTooltips;
import io.github.wurtzitane.avogadro.molecule.MoleculeStore;
import io.github.wurtzitane.avogadro.molecule.MoleculeStructure;

import net.minecraftforge.fluids.FluidStack;

/**
 * Client-only tools for drawing molecules, looking up client definitions, and exporting media.
 *
 * <p>
 * Call drawing methods from the render thread, such as inside a screen's drawing method. Do not load this class from
 * code that also runs on a dedicated server.
 */
public final class AvogadroClientApi {

    private AvogadroClientApi() {}

    /**
     * Returns the current molecule view.
     *
     * @return the current {@link MoleculeView}
     */
    public static MoleculeView view() {
        return MoleculeStore.view();
    }

    /**
     * Renders a molecule structure at the specified screen coordinates with a fixed size and angle.
     *
     * @param structure the molecule structure to render
     * @param x the x-coordinate on the screen
     * @param y the y-coordinate on the screen
     * @param size the render size (width and height)
     * @param angle the rotation angle
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
     * <p>
     * Call this from the render thread. Pass the number of seconds since your animation started; using the same time
     * value draws the same rotation.
     *
     * @param structure the molecule structure to render
     * @param x the x-coordinate on the screen
     * @param y the y-coordinate on the screen
     * @param width the render width
     * @param height the render height
     * @param options the rendering options to apply
     * @param elapsedSeconds seconds since your animation started
     */
    public static void render(MoleculeStructure structure, int x, int y, int width, int height,
            MoleculeRenderOptions options, double elapsedSeconds) {
        MoleculeRenderer.render(structure, x, y, width, height, options, elapsedSeconds);
    }

    /**
     * Returns the media export API instance.
     *
     * @return the {@link MediaExportApi}
     */
    public static MediaExportApi exports() {
        return MediaExportApi.INSTANCE;
    }

    /**
     * Executes drawing logic within a fluid tooltip context.
     *
     * @param fluid the fluid stack to display details for
     * @param draw the runnable containing the drawing logic
     */
    public static void withFluidTooltip(FluidStack fluid, Runnable draw) {
        MoleculeTooltips.withFluidTooltip(fluid, draw);
    }

    /**
     * Returns effective opaque ARGB sphere/cube colors after pack, programmatic, and local overrides.
     * Use the common {@link io.github.wurtzitane.avogadro.api.ElementColors} API to register RGB layers.
     * Retain this immutable snapshot for an operation that must not change color halfway through.
     * @return the current client palette; unchanged reads allocate nothing
     */
    public static io.github.wurtzitane.avogadro.client.ElementPalette.Snapshot elementColors() {
        return io.github.wurtzitane.avogadro.client.ElementPalette.snapshot();
    }

    /**
     * Reloads only element-color resources and the local partial JSON, without a full F3+T reload.
     * Work is scheduled on the Minecraft client thread; callers may invoke this method from any thread
     * in the client process. Never block that thread waiting for the returned future. Failed parsing
     * completes exceptionally and leaves the last valid palette active. Programmatic owner layers
     * remain installed, local user choices keep precedence, and existing exports remain unchanged.
     * @return future containing the resulting immutable effective palette, or the reload exception
     */
    public static java.util.concurrent.CompletableFuture<io.github.wurtzitane.avogadro.client.ElementPalette.Snapshot> reloadElementColors() {
        var future = new java.util.concurrent.CompletableFuture<io.github.wurtzitane.avogadro.client.ElementPalette.Snapshot>();
        var minecraft = net.minecraft.client.Minecraft.getMinecraft();
        Runnable reload = () -> {
            try { future.complete(io.github.wurtzitane.avogadro.client.ElementPalette.INSTANCE.reload(minecraft.getResourceManager())); }
            catch (Exception failure) { future.completeExceptionally(failure); }
        };
        if (minecraft.isCallingFromMinecraftThread()) reload.run(); else minecraft.addScheduledTask(reload);
        return future;
    }
}

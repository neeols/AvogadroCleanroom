package io.github.wurtzitane.avogadro.compat.probe;

import io.github.wurtzitane.avogadro.Config;
import io.github.wurtzitane.avogadro.client.MoleculeTooltips;
import io.github.wurtzitane.avogadro.client.WorldHudController;
import io.github.wurtzitane.avogadro.molecule.MoleculeDef;
import io.netty.buffer.ByteBuf;
import mcjty.theoneprobe.api.IElement;
import mcjty.theoneprobe.apiimpl.ProbeInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.World;
import org.apache.commons.lang3.tuple.Pair;

import java.util.Map;
import java.util.WeakHashMap;

/** Temporary client elements use the host's layout and never enter its network protocol. */
public final class ProbeHud {

    /** Table label element instance for entity probes. */
    private static IElement tableLabel;

    /** Thread-local target context for probe rendering. */
    private static final ThreadLocal<Target> TARGET = new ThreadLocal<>();

    /** Weak map tracking origin metadata for probe info instances. */
    private static final Map<ProbeInfo, Origin> ORIGINS = new WeakHashMap<>();

    /** Origin generation revision counter. */
    private static long originGeneration = Long.MIN_VALUE;

    /** Private constructor to prevent instantiation of utility class. */
    private ProbeHud() {}

    /**
     * Executes a runnable task with entity targeting set up for table labels.
     *
     * @param hit  ray trace result
     * @param draw runnable draw action
     */
    public static void withEntity(RayTraceResult hit, Runnable draw) {
        IElement previous = tableLabel;
        tableLabel = null;
        if (hit != null && hit.entityHit instanceof io.github.wurtzitane.avogadro.entity.PeriodicTableEntity table) {
            String label = io.github.wurtzitane.avogadro.client.PeriodicTableTarget.label(table);
            if (label != null) tableLabel = new mcjty.theoneprobe.apiimpl.elements.ElementText(label);
        }
        try { draw.run(); } finally { tableLabel = previous; }
    }

    /**
     * Executes a runnable task with world block targeting established for the probe HUD.
     *
     * @param hit  ray trace result
     * @param draw runnable draw action
     */
    public static void withTarget(RayTraceResult hit, Runnable draw) {
        Target previous = TARGET.get();
        Minecraft minecraft = Minecraft.getMinecraft();
        Target target = hit != null && hit.typeOfHit == RayTraceResult.Type.BLOCK && minecraft.world != null
                ? new Target(minecraft.world, hit, hit.getBlockPos().toImmutable(), WorldHudController.generation()) : null;
        if (target == null) TARGET.remove();
        else TARGET.set(target);
        try {
            draw.run();
        } finally {
            if (previous == null) TARGET.remove();
            else TARGET.set(previous);
        }
    }

    /**
     * Records a registered probe info origin for position and dimension validation.
     *
     * @param dimension world dimension ID
     * @param pos       block position
     * @param info      probe info instance
     */
    public static void registered(int dimension, BlockPos pos, ProbeInfo info) {
        if (pos == null || info == null || Minecraft.getMinecraft().world == null) return;
        long generation = WorldHudController.generation();
        synchronized (ORIGINS) {
            if (originGeneration != generation) {
                ORIGINS.clear();
                originGeneration = generation;
            }
            ORIGINS.put(info, new Origin(generation, dimension, pos.toImmutable()));
        }
    }

    /**
     * Retrieves or creates the molecule HUD element for the active probe target.
     *
     * @param info  probe info instance
     * @param cache cache mapping dimension and position to probe info entries
     * @return constructed {@link IElement} instance, or {@code null}
     */
    public static IElement element(ProbeInfo info, Map<Pair<Integer, BlockPos>, Pair<Long, ProbeInfo>> cache) {
        if (tableLabel != null) return tableLabel;
        Target target = TARGET.get();
        if (target == null || target.drawing || target.world != Minecraft.getMinecraft().world
                || target.generation != WorldHudController.generation()
                || !WorldHudController.active(Config.HudProvider.PROBE)) return null;
        int dimension = target.world.provider.getDimension();
        Pair<Long, ProbeInfo> entry = cache.get(Pair.of(dimension, target.pos));
        if (entry == null || entry.getRight() != info) return null;
        Origin origin;
        synchronized (ORIGINS) {
            origin = ORIGINS.get(info);
        }
        if (origin == null || origin.generation != target.generation || origin.dimension != dimension
                || !origin.pos.equals(target.pos)) return null;
        MoleculeDef definition = WorldHudController.match(target.world, target.pos, target.hit, null);
        if (definition == null) return null;
        target.drawing = true;
        return new MoleculeElement(definition, Config.TOOLTIP_SIZE.get());
    }

    /** Clears the drawing state flag on the current probe target once finished. */
    public static void finished() {
        Target target = TARGET.get();
        if (target != null) target.drawing = false;
    }

    /**
     * Record representing the origin context for a probe info registration.
     *
     * @param generation world generation counter
     * @param dimension  world dimension ID
     * @param pos        block position
     */
    private record Origin(long generation, int dimension, BlockPos pos) {}

    /** Target context container holding hit result, world reference, and generation. */
    private static final class Target {
        private final World world;
        private final RayTraceResult hit;
        private final BlockPos pos;
        private final long generation;
        private boolean drawing;

        /**
         * Constructs a new target context.
         *
         * @param world      world instance
         * @param hit        ray trace result
         * @param pos        block position
         * @param generation world generation counter
         */
        private Target(World world, RayTraceResult hit, BlockPos pos, long generation) {
            this.world = world;
            this.hit = hit;
            this.pos = pos;
            this.generation = generation;
        }
    }

    /**
     * Custom The One Probe element rendering molecule tooltips.
     *
     * @param definition molecule definition
     * @param size       tooltip display size
     */
    private record MoleculeElement(MoleculeDef definition, int size) implements IElement {

        /**
         * Retrieves element width in pixels.
         *
         * @return width integer value
         */
        @Override public int getWidth() { return size + 24; }

        /**
         * Retrieves element height in pixels.
         *
         * @return height integer value
         */
        @Override public int getHeight() { return size + 12; }

        /**
         * Renders the molecule tooltip element at given coordinates.
         *
         * @param x x render position
         * @param y y render position
         */
        @Override public void render(int x, int y) { MoleculeTooltips.draw(definition, x + 12, y + 6, size); }

        /**
         * Throws an exception because client-only elements cannot be serialized.
         *
         * @return element ID integer
         */
        @Override public int getID() { throw new IllegalStateException("Client-only molecule HUD element cannot be serialized"); }

        /**
         * Throws an exception because client-only elements cannot be serialized.
         *
         * @param buffer byte buffer destination
         */
        @Override public void toBytes(ByteBuf buffer) { throw new IllegalStateException("Client-only molecule HUD element cannot be serialized"); }
    }
}
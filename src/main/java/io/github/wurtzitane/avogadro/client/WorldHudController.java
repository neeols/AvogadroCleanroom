package io.github.wurtzitane.avogadro.client;

import io.github.wurtzitane.avogadro.Config;
import io.github.wurtzitane.avogadro.molecule.MoleculeDef;
import io.github.wurtzitane.avogadro.molecule.MoleculeStore;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.World;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.IFluidBlock;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.Display;

/** Input applies to world HUDs only; inventory tooltips never consult this controller. */
public final class WorldHudController {

    /** Current client world instance. */
    private static World world;

    /** Current network connection reference. */
    private static Object connection;

    /** World generation counter for cache tracking. */
    private static long generation;

    /** Toggle state flag for HUD visibility. */
    private static boolean toggled;

    /** Previous frame key press state. */
    private static boolean wasDown;

    /** Previous HUD mode configuration setting. */
    private static Config.HudMode previousMode;

    /** Previous HUD key code. */
    private static int previousKey;

    /** Private constructor to prevent instantiation of utility class. */
    private WorldHudController() {}

    /**
     * Handles client tick events to update world HUD toggle state and key triggers.
     *
     * @param event client tick event
     */
    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        refreshWorld();
        Minecraft minecraft = Minecraft.getMinecraft();
        Config.HudMode mode = Config.WORLD_HUD_MODE.get();
        int key = AvogadroKeys.WORLD_HUD.getKeyCode();
        if (mode != previousMode || key != previousKey) {
            toggled = false;
            wasDown = false;
            previousMode = mode;
            previousKey = key;
        }
        if (world == null || !focused() || minecraft.currentScreen != null
                || !Config.WORLD_HUD_ENABLED.get()) {
            toggled = false;
            wasDown = false;
            return;
        }
        boolean down = key != 0 && AvogadroKeys.isWorldHudDown();
        if (mode == Config.HudMode.TOGGLE && down && !wasDown) toggled = !toggled;
        wasDown = down;
    }

    /**
     * Checks whether the specified HUD provider is currently active and enabled.
     *
     * @param provider hud provider target
     * @return {@code true} if active, {@code false} otherwise
     */
    public static boolean active(Config.HudProvider provider) {
        refreshWorld();
        Minecraft minecraft = Minecraft.getMinecraft();
        if (world == null || !focused() || minecraft.currentScreen != null
                || !Config.WORLD_HUD_ENABLED.get() || AvogadroKeys.WORLD_HUD.getKeyCode() == 0
                || selectedProvider() != provider) return false;
        return Config.WORLD_HUD_MODE.get() == Config.HudMode.HOLD ? AvogadroKeys.isWorldHudDown() : toggled;
    }

    /** AUTO deliberately selects one adapter, leaving every host's own text untouched. */
    public static Config.HudProvider selectedProvider() {
        Config.HudProvider requested = Config.WORLD_HUD_PROVIDER.get();
        if (requested != Config.HudProvider.AUTO) return requested;
        return Loader.isModLoaded("theoneprobe") ? Config.HudProvider.PROBE : Config.HudProvider.WAILA;
    }

    /**
     * Retrieves the current world generation revision counter.
     *
     * @return generation long value
     */
    public static long generation() {
        refreshWorld();
        return generation;
    }

    /**
     * Checks whether the Minecraft client window has focus and input attention.
     *
     * @return {@code true} if focused, {@code false} otherwise
     */
    private static boolean focused() {
        // With pauseOnLostFocus disabled Minecraft can retain its input flag
        // after Alt-Tab. A latched HUD must still release when the window loses focus.
        return Minecraft.getMinecraft().inGameHasFocus && Display.isActive();
    }

    /** Refreshes world and connection state references, incrementing generation on change. */
    private static void refreshWorld() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (world == minecraft.world && connection == minecraft.getConnection()) return;
        world = minecraft.world;
        connection = minecraft.getConnection();
        generation++;
        toggled = false;
        wasDown = false;
    }

    /** Existing item/container matching, or a fluid identified directly by the world block. */
    public static MoleculeDef match(World world, BlockPos pos, RayTraceResult hit, ItemStack pick) {
        if (world == null || !world.isRemote || pos == null || !world.isBlockLoaded(pos)) return null;
        IBlockState state = world.getBlockState(pos);
        Block block = state.getBlock();
        Fluid fluid = block instanceof IFluidBlock fluidBlock ? fluidBlock.getFluid()
                : FluidRegistry.lookupFluidForBlock(block);
        if (fluid == null && block == Blocks.FLOWING_WATER) fluid = FluidRegistry.WATER;
        if (fluid == null && block == Blocks.FLOWING_LAVA) fluid = FluidRegistry.LAVA;
        ResourceLocation id = fluid == null ? null : MoleculeStore.lookup(fluid);
        if (id == null) {
            ItemStack stack = pick;
            if (stack == null) {
                if (hit == null || Minecraft.getMinecraft().player == null) return null;
                stack = block.getPickBlock(state, hit, world, pos, Minecraft.getMinecraft().player);
            }
            id = MoleculeStore.lookup(stack);
        }
        return id == null ? null : MoleculeStore.get(id);
    }
}
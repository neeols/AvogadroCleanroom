package io.github.wurtzitane.avogadro.compat;

import io.github.wurtzitane.avogadro.client.WorldHudController;
import io.github.wurtzitane.avogadro.compat.waila.WailaCompat;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Loader;

/** Called exclusively by the client proxy. Optional host classes stay out of common loading. */
public final class CompatibilityHooks {

    /** Flag indicating whether compatibility hooks have been initialized. */
    private static boolean initialized;

    /** Private constructor to prevent instantiation of utility class. */
    private CompatibilityHooks() {}

    /** Initializes client event buses and optional mod integrations such as WAILA. */
    public static void initialize() {
        if (initialized) return;
        initialized = true;
        MinecraftForge.EVENT_BUS.register(new WorldHudController());
        if (Loader.isModLoaded("waila")) WailaCompat.initialize();
    }
}
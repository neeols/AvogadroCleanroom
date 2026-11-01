package io.github.wurtzitane.avogadro;

import io.github.wurtzitane.avogadro.molecule.MoleculeNetwork;
import io.github.wurtzitane.avogadro.molecule.MoleculeRegistrations;
import io.github.wurtzitane.avogadro.molecule.MoleculeResources;
import io.github.wurtzitane.avogadro.molecule.MoleculeServerEvents;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppedEvent;
import net.minecraftforge.fml.common.network.NetworkCheckHandler;
import net.minecraftforge.fml.relauncher.Side;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Main mod class for Avogadro, handling lifecycle events, network version checks, configuration setup, and proxies. */
@Mod(modid = Avogadro.MODID, name = "Avogadro", version = Avogadro.VERSION,
        dependencies = "required-after:modularui@[3.1.6,)", acceptedMinecraftVersions = "[1.12.2]", acceptableRemoteVersions = "*",
        guiFactory = "io.github.wurtzitane.avogadro.client.AvogadroGuiFactory")
public final class Avogadro {

    /** Unique mod identifier string. */
    public static final String MODID = "avogadro";

    /** Mod version string. */
    public static final String VERSION = "1.0.0-cleanroom.57";

    /** Mod logger instance. */
    public static final Logger LOGGER = LogManager.getLogger(MODID);

    /** Root configuration directory path for Avogadro. */
    public static Path configDirectory;

    /** Sided proxy instance for client and server execution separation. */
    @SidedProxy(clientSide = "io.github.wurtzitane.avogadro.client.ClientProxy",
            serverSide = "io.github.wurtzitane.avogadro.CommonProxy")
    public static CommonProxy proxy;

    /**
     * Verifies remote mod version compatibility during network handshakes.
     *
     * @param mods       map of mod IDs to versions on the remote side
     * @param remoteSide remote side connection type
     * @return {@code true} if compatible, {@code false} otherwise
     */
    @NetworkCheckHandler
    public boolean compatibleRemote(Map<String, String> mods, Side remoteSide) {
        String remote = mods.get(MODID);
        if (remote == null || VERSION.equals(remote)) return true;
        LOGGER.warn("Avogadro requires matching client/server versions for molecule synchronization: local {}, remote {}", VERSION, remote);
        return false;
    }

    /**
     * @param event FML pre-initialization event instance
     */
    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        configDirectory = event.getModConfigurationDirectory().toPath().resolve(MODID);
        try {
            Files.createDirectories(configDirectory.resolve("molecules"));
            Files.createDirectories(configDirectory.resolve("server/data"));
        } catch (IOException e) {
            LOGGER.warn("Cannot create Avogadro configuration directories", e);
        }

        Config.load(event.getSuggestedConfigurationFile());
        io.github.wurtzitane.avogadro.molecule.SmilesGeometryCache.initialize(configDirectory.resolve("cache/smiles-v1"));
        io.github.wurtzitane.avogadro.item.MoleculeItems.initializeTabs();
        MoleculeRegistrations.initialize(MoleculeResources.readCommonDefaults());
        MoleculeNetwork.register();
        io.github.wurtzitane.avogadro.entity.PeriodicTableEntity.registerEntity();
        io.github.wurtzitane.avogadro.network.PeriodicTableNetwork.register();
        MinecraftForge.EVENT_BUS.register(new MoleculeServerEvents());
        proxy.preInit();
    }

    /**
     * @param event FML initialization event instance
     */
    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init();
        if (net.minecraftforge.fml.common.Loader.isModLoaded("theoneprobe"))
            net.minecraftforge.fml.common.event.FMLInterModComms.sendFunctionMessage("theoneprobe", "getTheOneProbe",
                    "io.github.wurtzitane.avogadro.compat.probe.PeriodicTableProbe");
    }

    /**
     * @param event FML server starting event instance
     */
    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        MoleculeServerEvents.start(event.getServer());
        event.registerServerCommand(new MoleculeServerEvents.ReloadCommand());
    }

    /**
     * @param event FML server stopped event instance
     */
    @Mod.EventHandler
    public void serverStopped(FMLServerStoppedEvent event) {
        MoleculeServerEvents.stop();
    }

    /**
     * Helper method for constructing resource locations within the Avogadro namespace.
     *
     * @param path resource path string
     * @return constructed {@link ResourceLocation} instance
     */
    public static ResourceLocation rl(String path) {
        return new ResourceLocation(MODID, path);
    }
}
package io.github.wurtzitane.avogadro.molecule;

import java.io.IOException;
import java.nio.file.Path;

import io.github.wurtzitane.avogadro.Avogadro;
import io.github.wurtzitane.avogadro.api.MoleculeView;
import io.github.wurtzitane.avogadro.api.MoleculesChangedEvent;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;

/** Server snapshots are independent of the integrated client's visible store. */
public final class MoleculeServerEvents {

    /** Current active server snapshot containing data, payloads, and views. */
    private static volatile Snapshot current;

    /** Active Minecraft server instance. */
    private static volatile MinecraftServer activeServer;

    /** Source data layers used for server loading. */
    private static Sources sources;

    /**
     * Container record for default, instance, and world-level resource sources.
     *
     * @param defaults default resource data
     * @param instance instance resource data
     * @param world world-level resource data
     */
    private record Sources(MoleculeResources.Data defaults, MoleculeResources.Data instance,
            MoleculeResources.Data world) {
    }

    /**
     * Container record representing a prepared server snapshot.
     *
     * @param data molecule resource data
     * @param payload encoded byte array payload
     * @param view molecule view instance
     */
    static record Snapshot(MoleculeResources.Data data, byte[] payload, MoleculeView view) {
    }

    public MoleculeServerEvents() {}

    /**
     * Starts server-side molecule event handling and reloads configurations.
     *
     * @param server Minecraft server instance
     */
    public static void start(MinecraftServer server) {
        activeServer = server;
        try {
            reload(server);
        } catch (CommandException rejected) {
            Avogadro.LOGGER.error(rejected.getMessage());
        }
    }

    /** Stops server event handling and clears active snapshot states. */
    public static void stop() {
        current = null;
        activeServer = null;
        sources = null;
    }

    /**
     * Returns the active Minecraft server instance.
     *
     * @return active {@link MinecraftServer}, or {@code null}
     */
    static MinecraftServer activeServer() {
        return activeServer;
    }

    /**
     * Returns the current molecule view from the active snapshot.
     *
     * @return current {@link MoleculeView}
     */
    public static MoleculeView view() {
        Snapshot snapshot = current;
        return snapshot == null ? MoleculeView.empty() : snapshot.view;
    }

    /**
     * Returns the current resource data from the active snapshot.
     *
     * @return current {@link MoleculeResources.Data}
     */
    public static MoleculeResources.Data data() {
        Snapshot snapshot = current;
        return snapshot == null ? MoleculeResources.Data.empty() : snapshot.data;
    }

    /**
     * Reloads server molecule resources from configuration and world directories.
     *
     * @param server Minecraft server instance
     * @return loaded molecule definition count
     * @throws CommandException if reloading fails
     */
    public static int reload(MinecraftServer server) throws CommandException {
        Path worldDirectory = server.getWorld(0).getSaveHandler().getWorldDirectory().toPath();
        Sources nextSources = new Sources(MoleculeResources.readCommonDefaults(),
                MoleculeResources.readSource(Avogadro.configDirectory.resolve("server"), "data"),
                MoleculeResources.readSource(worldDirectory.resolve("avogadro"), "data"));
        var layers = MoleculeRegistrations.layers();
        Snapshot prepared = prepareSnapshot(compose(nextSources, layers.mods(), layers.scripts()));
        sources = nextSources;
        publish(prepared);
        int count = prepared.view.all().size();
        Avogadro.LOGGER.info("Loaded {} server molecule definitions", count);
        return count;
    }

    /**
     * Validates and compresses once, then publishes data and payload together.
     *
     * @param next molecule resource data to install
     * @return installed molecule definition count
     * @throws CommandException if installation fails
     */
    static int installSnapshot(MoleculeResources.Data next) throws CommandException {
        current = prepareSnapshot(next);
        return current.view.all().size();
    }

    /**
     * Computes registration sources combining current sources with mod and script layers.
     *
     * @param mods mod molecule resource data
     * @param scripts script molecule resource data
     * @return composed {@link MoleculeResources.Data}
     */
    static MoleculeResources.Data registrationSources(MoleculeResources.Data mods, MoleculeResources.Data scripts) {
        Sources selected = sources;
        if (selected == null)
            selected = new Sources(MoleculeRegistrations.defaults(), MoleculeResources.Data.empty(),
                    MoleculeResources.Data.empty());
        return compose(selected, mods, scripts);
    }

    /**
     * Merges multiple source data layers and mod/script layers into a unified data structure.
     *
     * @param sources sources container
     * @param mods mod resource data
     * @param scripts script resource data
     * @return merged {@link MoleculeResources.Data}
     */
    private static MoleculeResources.Data compose(Sources sources, MoleculeResources.Data mods,
            MoleculeResources.Data scripts) {
        return MoleculeResources.mergeAll(sources.defaults, mods, sources.instance, sources.world, scripts);
    }

    /**
     * Prepares a server snapshot by loading definitions, encoding payloads, and building views.
     *
     * @param next target resource data
     * @return prepared {@link Snapshot}
     * @throws CommandException if encoding fails
     */
    static Snapshot prepareSnapshot(MoleculeResources.Data next) throws CommandException {
        MoleculeLoader.Loaded loaded = MoleculeLoader.load(next.molecules(), next.moleculeFallbacks());
        // Keep selected copy references raw so client overrides can still change their geometry.
        MoleculeResources.Data selected = new MoleculeResources.Data(loaded.raw(), next.itemTags(), next.fluidTags());
        byte[] payload;
        try {
            payload = MoleculeSync.encode(selected);
        } catch (IOException invalid) {
            throw new CommandException(
                    "Avogadro reload rejected: " + invalid.getMessage() + ". Previous snapshot retained.");
        }
        var itemTags = MoleculeResources.parseTags(next.itemTags());
        var fluidTags = MoleculeResources.parseTags(next.fluidTags());
        long revision = current == null ? 1 : current.view.revision() + 1;
        MoleculeView view = new MoleculeView(loaded.definitions(), itemTags, fluidTags, revision);
        return new Snapshot(selected, payload, view);
    }

    /**
     * Publishes a prepared snapshot to connected clients and notifies change listeners.
     *
     * @param snapshot snapshot to publish
     */
    static void publish(Snapshot snapshot) {
        current = snapshot;
        MinecraftServer server = activeServer;
        if (server != null) {
            for (EntityPlayerMP player : server.getPlayerList().getPlayers()) {
                try {
                    MoleculeNetwork.sendTo(player, snapshot.payload);
                } catch (RuntimeException failure) {
                    Avogadro.LOGGER.warn("Cannot send molecule snapshot to {}", player.getName(), failure);
                }
            }
        }
        MoleculeRegistrations
                .notifyChanged(new MoleculesChangedEvent(MoleculesChangedEvent.Scope.SERVER, snapshot.view, ""));
    }

    /**
     * Event subscriber handling player login events to synchronize molecule snapshots.
     *
     * @param event player logged in event instance
     */
    @SubscribeEvent
    public void login(PlayerEvent.PlayerLoggedInEvent event) {
        Snapshot snapshot = current;
        if (snapshot != null && event.player instanceof EntityPlayerMP player) {
            MoleculeNetwork.sendTo(player, snapshot.payload);
        }
    }

    /** Command handler for reloading Avogadro server configurations. */
    public static final class ReloadCommand extends CommandBase {

        /**
         * Returns the command name.
         *
         * @return command name string
         */
        @Override
        public String getName() {
            return "avogadro";
        }

        /**
         * Returns the command usage string.
         *
         * @param sender command sender instance
         * @return usage string
         */
        @Override
        public String getUsage(ICommandSender sender) {
            return "/avogadro reload";
        }

        /**
         * Returns the required permission level for executing the command.
         *
         * @return permission level integer
         */
        @Override
        public int getRequiredPermissionLevel() {
            return 2;
        }

        /**
         * Executes the reload command logic.
         *
         * @param server server instance
         * @param sender command sender instance
         * @param args command arguments array
         * @throws CommandException if execution fails
         */
        @Override
        public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
            if (args.length != 1 || !args[0].equals("reload")) {
                throw new WrongUsageException(getUsage(sender));
            }
            int count = reload(server);
            sender.sendMessage(new TextComponentString(
                    "Avogadro: reloaded " + count + " molecules and sent the snapshot to connected Avogadro clients."));
        }
    }
}

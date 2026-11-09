package io.github.wurtzitane.avogadro.client;

import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import io.github.wurtzitane.avogadro.Avogadro;
import io.github.wurtzitane.avogadro.api.MoleculesChangedEvent;
import io.github.wurtzitane.avogadro.molecule.MoleculeLoader;
import io.github.wurtzitane.avogadro.molecule.MoleculeRegistrations;
import io.github.wurtzitane.avogadro.molecule.MoleculeResources;
import io.github.wurtzitane.avogadro.molecule.MoleculeStore;

import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Manages client-side merged molecule definition stores, background loading tasks, and resource layering.
 * <p>
 * Client-only merged view. Server loads never mutate these static client indexes.
 */
public final class MoleculeClientStore {

    /** Built-in / default resource data layer. */
    private static MoleculeResources.Data builtins = MoleculeResources.Data.empty();

    /** Server-synchronized resource data layer. */
    private static MoleculeResources.Data server = MoleculeResources.Data.empty();

    /** Asset resource data layer. */
    private static MoleculeResources.Data assets = MoleculeResources.Data.empty();

    /** Local override resource data layer. */
    private static MoleculeResources.Data local = MoleculeResources.Data.empty();

    /** Flag indicating whether the client is currently connected to a server. */
    private static boolean connected;

    /** Flag indicating whether the event bus listener is currently registered. */
    private static boolean listening;

    /** Monotonically increasing generation counter tracking resource requests. */
    private static long generation;

    /** Currently requested source configuration snapshot. */
    private static Sources requested;

    /** Background future handle for definition preparation and compilation. */
    private static Future<Prepared> preparation;

    /** Background future handle for asynchronous local resource reloads. */
    private static Future<LocalLayers> localReload;

    /** Requested local reload task callable waiting to be submitted. */
    private static Callable<LocalLayers> requestedLocalReload;

    /**
     * Holds local layer resource data structures.
     *
     * @param defaults default resource data
     * @param assets asset resource data
     * @param local local override resource data
     */
    record LocalLayers(MoleculeResources.Data defaults, MoleculeResources.Data assets, MoleculeResources.Data local) {
    }

    /** Holds requested source generation and layer data. */
    private record Sources(long generation, MoleculeResources.Data common, MoleculeResources.Data assets,
            MoleculeResources.Data local) {
    }

    /** Holds prepared generation and compiled definition snapshots. */
    private record Prepared(long generation, MoleculeStore.CompiledSnapshot snapshot) {
    }

    private MoleculeClientStore() {}

    /**
     * Applies synchronized server molecule data and triggers a rebuild.
     *
     * @param data server resource data
     */
    public static void applyServerData(MoleculeResources.Data data) {
        server = data;
        connected = true;
        rebuild();
    }

    /**
     * Applies local resource layers and triggers a rebuild.
     *
     * @param defaults default resource data
     * @param resources asset resource data
     * @param overrides local override resource data
     */
    public static void applyLocal(MoleculeResources.Data defaults, MoleculeResources.Data resources,
            MoleculeResources.Data overrides) {
        listen();
        if (localReload != null) {
            localReload.cancel(true);
        }
        localReload = null;
        requestedLocalReload = null;
        builtins = defaults;
        assets = resources;
        local = overrides;
        rebuild();
    }

    /** Registers event bus listeners if not already listening. */
    private static void listen() {
        if (!listening) {
            MinecraftForge.EVENT_BUS.register(MoleculeClientStore.class);
            listening = true;
        }
    }

    /**
     * Reloads local resources asynchronously using the provided reader callable.
     *
     * @param reader callable returning local layer data
     */
    static void reloadLocal(Callable<LocalLayers> reader) {
        listen();
        if (localReload != null) {
            localReload.cancel(true);
        }
        requestedLocalReload = reader;
        localReload = null;
        if (preparation != null) {
            preparation.cancel(true);
        }
        preparation = null;
        requested = null;
        generation++;
        pump();
    }

    /** Handles disconnection from the server, resetting server data views. */
    public static void disconnect() {
        server = MoleculeResources.Data.empty();
        connected = false;
        rebuild();
    }

    /** Rebuilds the requested molecule definition sources. */
    private static void rebuild() {
        if (preparation != null) {
            preparation.cancel(true);
        }
        // Server snapshots already contain all common sources. Do not replay client-side
        // script registrations over them or retain a registration removed on the server.
        MoleculeResources.Data common = connected ? server : MoleculeRegistrations.localData(builtins);
        requested = new Sources(++generation, common, assets, local);
        preparation = null;
        submitDefinitions();
    }

    /** Submits definition loading and compilation tasks to the geometry preparation executor. */
    private static void submitDefinitions() {
        if (preparation != null || requested == null) {
            return;
        }
        Sources sources = requested;
        preparation = GeometryPreparation.submit(() -> {
            GeometryPreparation.checkCancelled();
            MoleculeResources.Data merged = MoleculeResources.mergeAll(sources.common, sources.assets, sources.local);
            // Parse after merging so a local copy may refer to a built-in/server molecule.
            var definitions = MoleculeLoader.load(merged.molecules(), merged.moleculeFallbacks()).definitions();
            GeometryPreparation.checkCancelled();
            var compiled = MoleculeStore.compileSnapshot(definitions, MoleculeResources.parseTags(merged.itemTags()),
                    MoleculeResources.parseTags(merged.fluidTags()));
            GeometryPreparation.checkCancelled();
            return new Prepared(sources.generation, compiled);
        });
    }

    /**
     * Pumps background reloads and definition preparation tasks.
     * <p>
     * Client-thread publication only; unfinished reloads keep the previous usable snapshot.
     */
    static void pump() {
        if (localReload == null && requestedLocalReload != null) {
            localReload = GeometryPreparation.submit(requestedLocalReload);
        }
        if (localReload != null && localReload.isDone()) {
            Future<LocalLayers> completed = localReload;
            localReload = null;
            requestedLocalReload = null;
            try {
                LocalLayers layers = completed.get();
                applyLocal(layers.defaults, layers.assets, layers.local);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (CancellationException ignored) {
            } catch (ExecutionException failure) {
                Avogadro.LOGGER.error("Cannot reload client molecule resources; keeping the previous definitions",
                        failure.getCause());
            }
        }
        submitDefinitions();
        if (preparation == null || !preparation.isDone()) {
            return;
        }
        Future<Prepared> completed = preparation;
        preparation = null;
        try {
            Prepared prepared = completed.get();
            if (prepared.generation != generation) {
                return;
            }
            requested = null;
            MoleculeRenderer.invalidateCaches();
            CrystalPreviewCache.retainDefinitions(prepared.snapshot.all());
            MoleculeStore.replaceAll(prepared.snapshot);
            Avogadro.LOGGER.info("Loaded {} client molecule definitions", prepared.snapshot.all().size());
            MoleculeRegistrations.notifyChanged(
                    new MoleculesChangedEvent(MoleculesChangedEvent.Scope.CLIENT, MoleculeStore.view(), ""));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (CancellationException ignored) {
        } catch (ExecutionException failure) {
            requested = null;
            Avogadro.LOGGER.error("Cannot prepare client molecule definitions; keeping the previous definitions",
                    failure.getCause());
        }
    }

    /**
     * Handles registration change events by scheduling a client store rebuild.
     *
     * @param event the molecules changed event
     */
    @SubscribeEvent
    public static void registrationsChanged(MoleculesChangedEvent event) {
        if (event.scope() == MoleculesChangedEvent.Scope.REGISTRATIONS) {
            Minecraft.getMinecraft().addScheduledTask(MoleculeClientStore::rebuild);
        }
    }
}

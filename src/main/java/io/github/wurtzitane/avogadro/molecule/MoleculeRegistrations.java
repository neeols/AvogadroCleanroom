package io.github.wurtzitane.avogadro.molecule;

import com.google.gson.JsonParser;
import io.github.wurtzitane.avogadro.Avogadro;
import io.github.wurtzitane.avogadro.api.MoleculeBatch;
import io.github.wurtzitane.avogadro.api.MoleculeRegistration;
import io.github.wurtzitane.avogadro.api.MoleculeView;
import io.github.wurtzitane.avogadro.api.MoleculesChangedEvent;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Manages retained molecule source layers, owner registrations, and authoritative server snapshot publications. */
public final class MoleculeRegistrations {

    /** Represents a registration owner with a name and script execution flag. */
    private record Owner(String name, boolean script) {}

    /** Map tracking active molecule resources owned by various entities. */
    private static final Map<Owner, MoleculeResources.Data> OWNERS = new LinkedHashMap<>();

    /** Flag indicating whether registration updates are currently being batched. */
    private static boolean editing;

    /** Default background molecule resource data. */
    private static MoleculeResources.Data defaults = MoleculeResources.Data.empty();

    /** Represents combined mod and script molecule resource data layers. */
    public record Layers(MoleculeResources.Data mods, MoleculeResources.Data scripts) {}

    /** Volatile container holding current mod and script data layers. */
    private static volatile Layers layers = new Layers(MoleculeResources.Data.empty(), MoleculeResources.Data.empty());

    /** Volatile molecule view instance representing current registered states. */
    private static volatile MoleculeView view = MoleculeView.empty();

    /** Private constructor to prevent instantiation of utility class. */
    private MoleculeRegistrations() {}

    /**
     * Initializes molecule registrations with default data sources and updates views.
     *
     * @param sources default molecule resource data
     */
    public static synchronized void initialize(MoleculeResources.Data sources) {
        defaults = sources;
        Layers current = layers;
        view = makeView(MoleculeResources.mergeAll(defaults, current.mods, current.scripts), view.revision() + 1);
    }

    /**
     * Creates or retrieves a registration owner instance with validation.
     *
     * @param name   owner name identifier string
     * @param script {@code true} if script-owned, {@code false} otherwise
     * @return constructed {@link MoleculeRegistration} instance
     * @throws IllegalArgumentException if name format is invalid
     */
    public static MoleculeRegistration owner(String name, boolean script) {
        if (name == null || !name.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException("A registration owner must be a lowercase mod identifier");
        }
        return new MoleculeRegistration(name, script);
    }

    /**
     * Retrieves the current data layers.
     *
     * @return current {@link Layers} instance
     */
    public static Layers layers() { return layers; }

    /**
     * Computes local combined molecule data incorporating defaults, mods, and scripts.
     *
     * @param defaults default molecule resource data
     * @return merged {@link MoleculeResources.Data} instance
     */
    public static MoleculeResources.Data localData(MoleculeResources.Data defaults) {
        Layers current = layers;
        return MoleculeResources.mergeAll(defaults, current.mods, current.scripts);
    }

    /**
     * Retrieves default molecule resource data.
     *
     * @return default {@link MoleculeResources.Data} instance
     */
    static MoleculeResources.Data defaults() { return defaults; }

    /**
     * Retrieves the current molecule view instance.
     *
     * @return current {@link MoleculeView} instance
     */
    public static MoleculeView view() { return view; }

    /**
     * Generates a preview molecule view incorporating temporary owner contents.
     *
     * @param owner    owner name string
     * @param script   {@code true} if script-owned, {@code false} otherwise
     * @param contents molecule resource data contents
     * @return preview {@link MoleculeView} instance
     */
    public static synchronized MoleculeView preview(String owner, boolean script, MoleculeResources.Data contents) {
        Map<Owner, MoleculeResources.Data> next = new LinkedHashMap<>(OWNERS);
        next.put(new Owner(owner, script), contents);
        return makeView(MoleculeServerEvents.registrationSources(combine(next, false), combine(next, true)), view.revision());
    }

    /**
     * Asynchronously commits changes to molecule registrations via a batch consumer.
     *
     * @param owner   owner name string
     * @param script  {@code true} if script-owned, {@code false} otherwise
     * @param replace {@code true} to replace existing data, {@code false} to merge
     * @param changes batch consumer operation
     * @return completable future resolving to the updated {@link MoleculeView}
     */
    public static CompletableFuture<MoleculeView> commit(String owner, boolean script, boolean replace,
                                                         Consumer<MoleculeBatch> changes) {
        Objects.requireNonNull(changes, "changes");
        return dispatch(() -> install(new Owner(owner, script), replace ? MoleculeResources.Data.empty() : null, changes));
    }

    /**
     * Asynchronously replaces molecule registrations with specified contents.
     *
     * @param owner    owner name string
     * @param script   {@code true} if script-owned, {@code false} otherwise
     * @param contents new molecule resource data contents
     * @return completable future resolving to the updated {@link MoleculeView}
     */
    public static CompletableFuture<MoleculeView> replace(String owner, boolean script, MoleculeResources.Data contents) {
        return dispatch(() -> install(new Owner(owner, script), Objects.requireNonNull(contents, "contents"), batch -> {}));
    }

    /**
     * Dispatches commit actions onto the main Minecraft server thread if necessary.
     *
     * @param commit callable task returning a molecule view
     * @return completable future for the task result
     */
    private static CompletableFuture<MoleculeView> dispatch(java.util.concurrent.Callable<MoleculeView> commit) {
        CompletableFuture<MoleculeView> result = new CompletableFuture<>();
        Runnable action = () -> {
            try { result.complete(commit.call()); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
        };
        var server = MoleculeServerEvents.activeServer();
        if (server != null && !server.isCallingFromMinecraftThread()) server.addScheduledTask(action);
        else action.run();
        return result;
    }

    /**
     * Installs new registration changes, validates definitions, updates snapshots, and publishes events.
     *
     * @param owner       owner instance
     * @param replacement replacement data, or {@code null}
     * @param changes     batch changes consumer
     * @return updated {@link MoleculeView} instance
     * @throws Exception if validation or installation fails
     */
    private static synchronized MoleculeView install(Owner owner, MoleculeResources.Data replacement,
                                                     Consumer<MoleculeBatch> changes) throws Exception {
        // Validate even callers constructing handles directly; ownership never identifies an arbitrary path.
        owner(owner.name, owner.script);
        if (editing) throw new IllegalStateException("Registration updates cannot be nested inside a batch callback");
        Map<Owner, MoleculeResources.Data> next = new LinkedHashMap<>(OWNERS);
        MoleculeBatch batch = new MoleculeBatch(replacement == null
                ? next.getOrDefault(owner, MoleculeResources.Data.empty()) : replacement);
        editing = true;
        try { changes.accept(batch); }
        finally { editing = false; }
        next.put(owner, batch.data());
        MoleculeResources.Data nextMods = combine(next, false);
        MoleculeResources.Data nextScripts = combine(next, true);
        MoleculeResources.Data context = MoleculeServerEvents.registrationSources(nextMods, nextScripts);
        List<String> contextErrors = new ArrayList<>();
        var contextDefinitions = MoleculeLoader.parseAll(context.molecules(), contextErrors::add);
        for (MoleculeResources.Data owned : next.values()) validate(owned, context, contextDefinitions, contextErrors);
        var prepared = MoleculeServerEvents.prepareSnapshot(context);
        MoleculeView registered = makeView(MoleculeResources.mergeAll(defaults, nextMods, nextScripts), view.revision() + 1);
        OWNERS.clear();
        OWNERS.putAll(next);
        layers = new Layers(nextMods, nextScripts);
        view = registered;
        if (MoleculeServerEvents.activeServer() != null) MoleculeServerEvents.publish(prepared);
        notifyChanged(new MoleculesChangedEvent(MoleculesChangedEvent.Scope.REGISTRATIONS, registered, owner.name));
        return MoleculeServerEvents.activeServer() == null ? registered : MoleculeServerEvents.view();
    }

    /**
     * Combines owner resources filtered by script status.
     *
     * @param owners map of owners to their resource data
     * @param script script filtering flag
     * @return combined {@link MoleculeResources.Data} instance
     */
    private static MoleculeResources.Data combine(Map<Owner, MoleculeResources.Data> owners, boolean script) {
        var result = new MoleculeResources.Accumulator(MoleculeResources.Data.empty());
        for (var entry : owners.entrySet()) if (entry.getKey().script == script) result.add(entry.getValue());
        return result.toData();
    }

    /**
     * Validates owned molecule definitions and tags against current context rules and error lists.
     *
     * @param owned              owned molecule resource data
     * @param context            full context molecule resource data
     * @param contextDefinitions map of pre-parsed context definitions
     * @param contextErrors      list of context error strings
     * @throws IllegalArgumentException if validation checks fail
     */
    private static void validate(MoleculeResources.Data owned, MoleculeResources.Data context,
                                 Map<ResourceLocation, MoleculeDef> contextDefinitions, List<String> contextErrors) {
        Map<ResourceLocation, MoleculeDef> definitions = contextDefinitions;
        List<String> errors = contextErrors;
        if (!owned.molecules().entrySet().stream().allMatch(entry -> entry.getValue().equals(context.molecules().get(entry.getKey())))) {
            Map<ResourceLocation, String> raw = new LinkedHashMap<>(context.molecules());
            raw.putAll(owned.molecules());
            errors = new ArrayList<>();
            definitions = MoleculeLoader.parseAll(raw, errors::add);
        }
        for (ResourceLocation id : owned.molecules().keySet()) {
            if (!definitions.containsKey(id)) throw new IllegalArgumentException("Invalid registration " + id + ": " + String.join("; ", errors));
        }
        owned.itemTags().forEach(MoleculeRegistrations::validateTag);
        owned.fluidTags().forEach(MoleculeRegistrations::validateTag);
    }

    /**
     * Validates tag JSON syntax, allowed fields, and value identifiers.
     *
     * @param id   tag resource location identifier
     * @param json tag JSON string
     * @throws IllegalArgumentException if tag syntax or fields are invalid
     */
    private static void validateTag(ResourceLocation id, String json) {
        var object = JsonParser.parseString(json).getAsJsonObject();
        for (String field : object.keySet()) {
            if (!Set.of("values", "replace").contains(field)) throw new IllegalArgumentException("Unsupported tag field " + field + " in " + id);
        }
        if (object.has("replace") && (!object.get("replace").isJsonPrimitive()
                || !object.get("replace").getAsJsonPrimitive().isBoolean())) throw new IllegalArgumentException("Tag replace must be boolean: " + id);
        if (!object.has("values") || !object.get("values").isJsonArray()) throw new IllegalArgumentException("Tag values must be an array: " + id);
        for (var value : object.getAsJsonArray("values")) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Tag values must be strings: " + id);
            String text = value.getAsString();
            MoleculeLoader.identifier(text.startsWith("#") ? text.substring(1) : text);
        }
    }

    /**
     * Constructs a molecule view from resource data and revision numbers.
     *
     * @param data     molecule resource data
     * @param revision revision tracking long
     * @return constructed {@link MoleculeView} instance
     */
    static MoleculeView makeView(MoleculeResources.Data data, long revision) {
        return new MoleculeView(MoleculeLoader.load(data.molecules(), data.moleculeFallbacks()).definitions(),
                MoleculeResources.parseTags(data.itemTags()), MoleculeResources.parseTags(data.fluidTags()), revision);
    }

    /**
     * Posts a molecules changed event to the Forge event bus, logging errors if listeners fail.
     *
     * @param event molecules changed event instance
     */
    public static void notifyChanged(MoleculesChangedEvent event) {
        try { MinecraftForge.EVENT_BUS.post(event); }
        catch (RuntimeException failure) { Avogadro.LOGGER.error("Molecule change listener failed after snapshot publication", failure); }
    }
}
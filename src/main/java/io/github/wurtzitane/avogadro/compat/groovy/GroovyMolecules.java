package io.github.wurtzitane.avogadro.compat.groovy;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.cleanroommc.groovyscript.GroovyScript;
import com.cleanroommc.groovyscript.api.GroovyBlacklist;
import com.cleanroommc.groovyscript.api.GroovyLog;
import com.cleanroommc.groovyscript.registry.VirtualizedRegistry;
import com.cleanroommc.groovyscript.sandbox.LoadStage;

import io.github.wurtzitane.avogadro.api.MoleculeBatch;
import io.github.wurtzitane.avogadro.api.MoleculeBuilder;
import io.github.wurtzitane.avogadro.api.MoleculeRegistration;
import io.github.wurtzitane.avogadro.api.MoleculeSpec;
import io.github.wurtzitane.avogadro.api.MoleculeView;
import io.github.wurtzitane.avogadro.api.StructureBuilder;
import io.github.wurtzitane.avogadro.api.StructureFragment;
import io.github.wurtzitane.avogadro.molecule.MoleculeDef;
import io.github.wurtzitane.avogadro.molecule.MoleculeRegistrations;

import groovy.lang.Closure;

/** Reloadable script contribution above server JSON, using the same builder and validation as Java. */
public final class GroovyMolecules extends VirtualizedRegistry<MoleculeSpec> {

    /** Owner identifier string for GroovyScript registrations. */
    private static final String OWNER = "groovyscript";

    /** Molecule registration instance owned by GroovyScript. */
    private static final MoleculeRegistration registration = MoleculeRegistrations.owner(OWNER, true);

    /** Pending molecule batch for script contributions. */
    private MoleculeBatch pending = new MoleculeBatch();

    /** Flag indicating whether a registration error occurred during the script run. */
    private boolean rejected;

    /** Creates a GroovyMolecules registry with the specified registry paths. */
    public GroovyMolecules() {
        super(List.of("molecules"));
    }

    /**
     * Creates a new molecule builder for a given identifier.
     *
     * @param id molecule identifier
     * @return constructed {@link MoleculeBuilder}
     */
    public MoleculeBuilder builder(String id) {
        return new MoleculeBuilder(id);
    }

    /**
     * Calculates the molecular weight result for a formula string.
     *
     * @param formula chemical formula string
     * @return weight result instance
     */
    public io.github.wurtzitane.avogadro.api.MoleculeWeight.Result weight(String formula) {
        return io.github.wurtzitane.avogadro.api.MoleculeWeight.calculate(formula);
    }

    /**
     * Calculates the molecular weight result for a molecule definition.
     *
     * @param molecule molecule definition
     * @return weight result instance
     */
    public io.github.wurtzitane.avogadro.api.MoleculeWeight.Result weight(MoleculeDef molecule) {
        return io.github.wurtzitane.avogadro.api.MoleculeWeight.calculate(molecule);
    }

    /**
     * Returns an item stack for a given molecule identifier.
     *
     * @param id molecule identifier
     * @return item stack instance
     */
    public net.minecraft.item.ItemStack item(String id) {
        return io.github.wurtzitane.avogadro.item.MoleculeItems.stack(get(id));
    }

    /**
     * Creates a new structure assembly builder.
     *
     * @return constructed structure assembly builder
     */
    public io.github.wurtzitane.avogadro.api.StructureAssembly.Builder assemblyBuilder() {
        return io.github.wurtzitane.avogadro.api.StructureAssembly.builder();
    }

    /**
     * Creates a new structure edits builder.
     *
     * @return constructed structure edits builder
     */
    public io.github.wurtzitane.avogadro.api.StructureEdits.Builder edits() {
        return io.github.wurtzitane.avogadro.api.StructureEdits.builder();
    }

    /**
     * Defines and builds a structure assembly using a Groovy closure definition.
     *
     * @param definition closure definition
     * @return constructed {@link io.github.wurtzitane.avogadro.api.StructureAssembly}
     */
    public io.github.wurtzitane.avogadro.api.StructureAssembly assembly(Closure<?> definition) {
        var builder = assemblyBuilder();
        call(definition, builder);
        return builder.build();
    }

    /**
     * Creates a new structure builder.
     *
     * @return constructed structure builder instance
     */
    public StructureBuilder structureBuilder() {
        return new StructureBuilder();
    }

    /**
     * Builds a structure fragment using a Groovy closure definition.
     *
     * @param definition closure definition
     * @return constructed {@link StructureFragment}
     */
    public StructureFragment structure(Closure<?> definition) {
        StructureBuilder builder = structureBuilder();
        call(definition, builder);
        return builder.build();
    }

    /**
     * Creates a molecule builder copying from an existing source identifier.
     *
     * @param id new molecule identifier
     * @param source source molecule identifier
     * @return constructed molecule builder
     */
    public MoleculeBuilder copy(String id, String source) {
        return builder(id).copy(source);
    }

    /**
     * Registers a molecule specification definition.
     *
     * @param definition molecule spec
     * @return registered molecule spec
     */
    public MoleculeSpec register(MoleculeSpec definition) {
        return stage(() -> {
            pending.register(definition);
            return definition;
        });
    }

    /**
     * Registers a molecule from a builder definition.
     *
     * @param definition molecule builder
     * @return registered molecule spec
     */
    public MoleculeSpec register(MoleculeBuilder definition) {
        return stage(() -> register(definition.build()));
    }

    /**
     * Registers a molecule from an identifier and property map.
     *
     * 0 * @param id molecule identifier
     *
     * @param fields field properties map
     * @return registered molecule spec
     */
    public MoleculeSpec register(String id, Map<?, ?> fields) {
        return stage(() -> register(MoleculeBuilder.from(id, fields)));
    }

    /**
     * Registers a molecule using an identifier and closure definition.
     *
     * @param id molecule identifier
     * @param definition closure definition
     * @return registered molecule spec
     */
    public MoleculeSpec register(String id, Closure<?> definition) {
        return stage(() -> {
            MoleculeBuilder builder = builder(id);
            call(definition, builder);
            return register(builder);
        });
    }

    /**
     * Removes a molecule registration by identifier.
     *
     * @param id molecule identifier
     */
    public void remove(String id) {
        stage(() -> {
            pending.remove(id);
            return null;
        });
    }

    /** Clears all pending molecule registrations. */
    public void clear() {
        stage(() -> {
            pending.clear();
            return null;
        });
    }

    /**
     * Adds item tag bindings to a molecule definition without replacing existing tags.
     *
     * @param id molecule identifier
     * @param values tag collection values
     */
    public void itemTag(String id, Collection<?> values) {
        itemTag(id, false, values);
    }

    /**
     * Adds item tag bindings to a molecule definition, optionally replacing existing tags.
     *
     * @param id molecule identifier
     * @param replace whether to replace existing tags
     * @param values tag collection values
     */
    public void itemTag(String id, boolean replace, Collection<?> values) {
        stage(() -> {
            pending.itemTag(id, replace, strings(values));
            return null;
        });
    }

    /**
     * Adds fluid tag bindings to a molecule definition without replacing existing tags.
     *
     * @param id molecule identifier
     * @param values tag collection values
     */
    public void fluidTag(String id, Collection<?> values) {
        fluidTag(id, false, values);
    }

    /**
     * Adds fluid tag bindings to a molecule definition, optionally replacing existing tags.
     *
     * @param id molecule identifier
     * @param replace whether to replace existing tags
     * @param values tag collection values
     */
    public void fluidTag(String id, boolean replace, Collection<?> values) {
        stage(() -> {
            pending.fluidTag(id, replace, strings(values));
            return null;
        });
    }

    /**
     * Removes item tag bindings from a molecule definition.
     *
     * @param id molecule identifier
     */
    public void removeItemTag(String id) {
        stage(() -> {
            pending.removeItemTag(id);
            return null;
        });
    }

    /**
     * Removes fluid tag bindings from a molecule definition.
     *
     * @param id molecule identifier
     */
    public void removeFluidTag(String id) {
        stage(() -> {
            pending.removeFluidTag(id);
            return null;
        });
    }

    /**
     * Returns the current molecule view snapshot.
     *
     * @return molecule view instance
     */
    public MoleculeView view() {
        return MoleculeRegistrations.preview(OWNER, true, pending.data());
    }

    /**
     * Returns a molecule definition by identifier.
     *
     * @param id molecule identifier string
     * @return molecule definition, or {@code null}
     */
    public MoleculeDef get(String id) {
        return view().get(id);
    }

    /**
     * Returns all registered molecule definitions.
     *
     * @return list of molecule definitions
     */
    public List<MoleculeDef> all() {
        return view().all();
    }

    /**
     * Executes a batch of changes within a transactional closure.
     *
     * @param changes changes closure
     */
    public void batch(Closure<?> changes) {
        stage(() -> {
            MoleculeBatch previous = pending;
            pending = new MoleculeBatch(previous.data());
            try {
                call(changes, this);
            } catch (RuntimeException failure) {
                pending = previous;
                throw failure;
            }
            return null;
        });
    }

    /** Reloads the registry by resetting pending batch and rejection flag. */
    @Override
    @GroovyBlacklist
    public void onReload() {
        pending = new MoleculeBatch();
        rejected = false;
    }

    /** Applies pending script contributions after script loading concludes. */
    @Override
    @GroovyBlacklist
    public void afterScriptLoad() {
        if (rejected) {
            GroovyLog.get().warn(
                    "Avogadro registrations retained because an Avogadro operation failed during this script run");
            return;
        }
        try {
            registration.replace(pending).join();
        } catch (RuntimeException failure) {
            rejected = true;
            GroovyLog.get().exception("Avogadro script registrations were rejected; previous contribution retained",
                    failure);
        }
    }

    /**
     * Executes an action within the post-init stage validator.
     *
     * @param action supplier action
     * @param <T> return type
     * @return action result
     */
    private <T> T stage(Supplier<T> action) {
        try {
            if (GroovyScript.getSandbox().getCurrentLoader() != LoadStage.POST_INIT) {
                throw new IllegalStateException(
                        "Avogadro script registrations belong in the reloadable postInit loader");
            }
            return action.get();
        } catch (RuntimeException failure) {
            rejected = true;
            throw failure;
        }
    }

    /**
     * Converts a collection of tag values into a string array.
     *
     * @param values collection of tag values
     * @return string array of registry IDs or tag names
     */
    private static String[] strings(Collection<?> values) {
        return values.stream().map(value -> {
            if (!(value instanceof CharSequence) && !(value instanceof net.minecraft.util.ResourceLocation)) {
                throw new IllegalArgumentException("Tag values must be registry ids or #tag names");
            }
            return value.toString();
        }).toArray(String[]::new);
    }

    /**
     * Rehydrates and invokes a Groovy closure with a specified delegate.
     *
     * @param closure groovy closure
     * @param delegate delegate object
     */
    private static void call(Closure<?> closure, Object delegate) {
        Closure<?> scoped = (Closure<?>) closure.rehydrate(delegate, closure.getOwner(), closure.getThisObject());
        scoped.setResolveStrategy(Closure.DELEGATE_FIRST);
        if (scoped.getMaximumNumberOfParameters() == 0)
            scoped.call();
        else
            scoped.call(delegate);
    }
}

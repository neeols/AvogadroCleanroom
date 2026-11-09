package io.github.wurtzitane.avogadro.api;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import io.github.wurtzitane.avogadro.molecule.MoleculeRegistrations;

/**
 * Manages the molecule definitions registered by one mod or pack.
 *
 * <p>
 * The owner name keeps your registrations separate from other contributors. Changes made while a server is running are
 * applied on the server thread. The returned future completes when the new view is ready.
 */
public final class MoleculeRegistration {

    /** The owner identifier associated with this registration scope. */
    private final String owner;

    /** Indicates whether this registration layer originates from a script. */
    private final boolean script;

    /**
     * Creates a {@link MoleculeRegistration} with the specified owner and script flag.
     *
     * @param owner the owner identifier string
     * @param script {@code true} if managed by a script, {@code false} otherwise
     */
    public MoleculeRegistration(String owner, boolean script) {
        this.owner = owner;
        this.script = script;
    }

    /**
     * Returns the owner identifier of this registration layer.
     *
     * @return the owner string
     */
    public String owner() {
        return owner;
    }

    /**
     * Registers a molecule specification under this owner.
     *
     * @param definition the {@link MoleculeSpec} to register
     * @return a {@link CompletableFuture} resolving to the updated {@link MoleculeView}
     */
    public CompletableFuture<MoleculeView> register(MoleculeSpec definition) {
        return update(batch -> batch.register(definition));
    }

    /**
     * Builds and registers a molecule definition under this owner.
     *
     * @param definition the {@link MoleculeBuilder} containing the definition
     * @return a {@link CompletableFuture} resolving to the updated {@link MoleculeView}
     */
    public CompletableFuture<MoleculeView> register(MoleculeBuilder definition) {
        return register(definition.build());
    }

    /**
     * Unregisters a molecule entry by its identifier string under this owner.
     *
     * @param id the string identifier of the molecule to remove
     * @return a {@link CompletableFuture} resolving to the updated {@link MoleculeView}
     */
    public CompletableFuture<MoleculeView> unregister(String id) {
        return update(batch -> batch.remove(id));
    }

    /**
     * Clears all registered definitions and tags under this owner.
     *
     * @return a {@link CompletableFuture} resolving to the updated {@link MoleculeView}
     */
    public CompletableFuture<MoleculeView> clear() {
        return replace(new MoleculeBatch());
    }

    /**
     * Updates this owner's registration batch using a consumer function.
     *
     * @param changes a {@link Consumer} modifying the {@link MoleculeBatch}
     * @return a {@link CompletableFuture} resolving to the updated {@link MoleculeView}
     */
    public CompletableFuture<MoleculeView> update(Consumer<MoleculeBatch> changes) {
        return MoleculeRegistrations.commit(owner, script, false, changes);
    }

    /**
     * Replaces this owner's complete registration contents with a new batch.
     *
     * @param contents the new {@link MoleculeBatch} containing replacement contents
     * @return a {@link CompletableFuture} resolving to the updated {@link MoleculeView}
     */
    public CompletableFuture<MoleculeView> replace(MoleculeBatch contents) {
        return MoleculeRegistrations.replace(owner, script, contents.data());
    }
}

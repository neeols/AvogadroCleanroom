package io.github.wurtzitane.avogadro.api;

import net.minecraftforge.fml.common.eventhandler.Event;

/**
 * Published after a complete snapshot is installed.
 * <p>Views remain valid after the callback.
 */
public final class MoleculesChangedEvent extends Event {

    /**
     * Defines the scope of the molecules change event.
     */
    public enum Scope {
        /** Scope covering base registrations. */
        REGISTRATIONS,
        /** Scope covering server-side state. */
        SERVER,
        /** Scope covering client-side local overrides. */
        CLIENT
    }

    /** The scope in which the change occurred. */
    private final Scope scope;

    /** The updated molecule view snapshot. */
    private final MoleculeView view;

    /** The owner identifier associated with the change, or an empty string if global. */
    private final String owner;

    /**
     * Default constructor for Forge event bus instantiation while registering listeners.
     */
    public MoleculesChangedEvent() {
        this(Scope.REGISTRATIONS, MoleculeView.empty(), "");
    }

    /**
     * Constructs a new {@link MoleculesChangedEvent} with the specified scope, view snapshot, and owner.
     *
     * @param scope the {@link Scope} of the change
     * @param view  the updated {@link MoleculeView} snapshot
     * @param owner the owner string associated with the change
     */
    public MoleculesChangedEvent(Scope scope, MoleculeView view, String owner) {
        this.scope = scope;
        this.view = view;
        this.owner = owner;
    }

    /**
     * Retrieves the scope of this event.
     *
     * @return the {@link Scope} instance
     */
    public Scope scope() {
        return scope;
    }

    /**
     * Retrieves the updated molecule view snapshot.
     *
     * @return the {@link MoleculeView} instance
     */
    public MoleculeView view() {
        return view;
    }

    /**
     * Retrieves the owner identifier associated with the change.
     *
     * @return the owner string
     */
    public String owner() {
        return owner;
    }
}
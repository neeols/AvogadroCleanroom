package io.github.wurtzitane.avogadro.compat.groovy;

import com.cleanroommc.groovyscript.api.GroovyPlugin;
import com.cleanroommc.groovyscript.compat.mods.GroovyContainer;
import com.cleanroommc.groovyscript.compat.mods.GroovyPropertyContainer;
import io.github.wurtzitane.avogadro.Avogadro;

/** Discovered by GroovyScript's ASM scan; common Avogadro initialization never loads this class. */
public final class AvogadroGroovyPlugin implements GroovyPlugin {

    /**
     * Retrieves the mod identifier associated with this plugin.
     *
     * @return mod ID string
     */
    @Override public String getModId() { return Avogadro.MODID; }

    /**
     * Retrieves the display name of the container.
     *
     * @return container name string
     */
    @Override public String getContainerName() { return "Avogadro"; }

    /**
     * Called when compatibility is loaded for the groovy container.
     *
     * @param container groovy container instance
     */
    @Override public void onCompatLoaded(GroovyContainer<?> container) {}

    /**
     * Creates and returns the Groovy property container instance.
     *
     * @return constructed {@link GroovyPropertyContainer} instance
     */
    @Override public GroovyPropertyContainer createGroovyPropertyContainer() { return new Container(); }

    /**
     * Property container exposing Groovy scripting integrations for Avogadro molecules.
     */
    public static final class Container extends GroovyPropertyContainer {

        /** Groovy molecules helper container instance. */
        public final GroovyMolecules molecules = new GroovyMolecules();
    }
}
package io.github.wurtzitane.avogadro.client;

import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import org.lwjgl.input.Keyboard;

/**
 * Manages keybindings and input registration for the Avogadro mod.
 */
public final class AvogadroKeys {

    /** Keybinding for pausing molecule animations within GUI screens. */
    public static final KeyBinding PAUSE_MOLECULE = new KeyBinding("key.avogadro.pauseMolecule",
            net.minecraftforge.client.settings.KeyConflictContext.GUI, Keyboard.KEY_SPACE, "key.categories.avogadro");

    /** Keybinding for examining molecules with Shift+N within GUI screens. */
    public static final KeyBinding EXAMINE_MOLECULE = new KeyBinding("key.avogadro.examineMolecule",
            net.minecraftforge.client.settings.KeyConflictContext.GUI, net.minecraftforge.client.settings.KeyModifier.SHIFT,
            Keyboard.KEY_N, "key.categories.avogadro");

    /** Keybinding for toggling or displaying the world HUD. */
    public static final KeyBinding WORLD_HUD = new KeyBinding("key.avogadro.worldHud", Keyboard.KEY_NONE,
            "key.categories.avogadro");

    /** Private constructor to prevent instantiation of utility class. */
    private AvogadroKeys() {}

    /** Registers all Avogadro keybindings with the client registry. */
    public static void register() {
        ClientRegistry.registerKeyBinding(WORLD_HUD);
        ClientRegistry.registerKeyBinding(PAUSE_MOLECULE);
        ClientRegistry.registerKeyBinding(EXAMINE_MOLECULE);
    }

    /**
     * Checks whether the world HUD keybinding is currently pressed down.
     *
     * @return {@code true} if bound and pressed down, {@code false} otherwise
     */
    public static boolean isWorldHudDown() {
        return WORLD_HUD.getKeyCode() != Keyboard.KEY_NONE && WORLD_HUD.isKeyDown();
    }
}
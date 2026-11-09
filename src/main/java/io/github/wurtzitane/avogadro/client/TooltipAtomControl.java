package io.github.wurtzitane.avogadro.client;

import io.github.wurtzitane.avogadro.Config;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;

import org.lwjgl.opengl.Display;

/** Polls the GUI modifier once per render frame, avoiding keyboard-repeat events and tooltip-draw side effects. */
final class TooltipAtomControl {
    private static final State STATE = new State();
    private static boolean active;

    private TooltipAtomControl() {}

    static void poll() {
        var client = Minecraft.getMinecraft();
        var binding = AvogadroKeys.TOOLTIP_ATOM;
        boolean enabled = client.currentScreen != null && Display.isActive() && Config.ENABLE_TOOLTIPS.get()
                && binding.getKeyCode() != 0;
        boolean down = enabled && binding.getKeyConflictContext().isActive()
                && binding.getKeyModifier().isActive(binding.getKeyConflictContext()) && GameSettings.isKeyDown(binding);
        active = STATE.update(Config.TOOLTIP_ATOM_MODE.get(), binding.getKeyCode(), down, enabled);
    }

    static boolean active() { return active; }

    static void reset() {
        STATE.reset();
        active = false;
    }

    /** Input-only state is separate from the renderer, so switching hovered items cannot restart an animation. */
    static final class State {
        private Config.HudMode previousMode;
        private int previousKey;
        private boolean wasDown, toggled;

        boolean update(Config.HudMode mode, int key, boolean down, boolean enabled) {
            if (mode != previousMode || key != previousKey) {
                previousMode = mode;
                previousKey = key;
                toggled = false;
                wasDown = down;
            }
            if (!enabled || key == 0) {
                toggled = false;
                wasDown = down;
                return false;
            }
            if (mode == Config.HudMode.TOGGLE && down && !wasDown) toggled = !toggled;
            wasDown = down;
            return mode == Config.HudMode.HOLD ? down : toggled;
        }

        void reset() {
            previousMode = null;
            wasDown = toggled = false;
        }
    }
}

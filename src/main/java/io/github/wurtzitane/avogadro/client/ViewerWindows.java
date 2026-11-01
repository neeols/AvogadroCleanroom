package io.github.wurtzitane.avogadro.client;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;
import com.cleanroommc.modularui.factory.ClientGUI;
import com.cleanroommc.modularui.screen.GuiScreenWrapper;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.screen.UISettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.resources.I18n;
import net.minecraftforge.fml.common.Loader;

/** Manages multi-window offscreen GUI hosting and window integration for Avogadro viewer screens. */
final class ViewerWindows {

    /** Private constructor to prevent instantiation of utility class. */
    private ViewerWindows() {}

    /**
     * Checks whether offscreen window hosting is available.
     *
     * @return {@code true} if available, {@code false} otherwise
     */
    static boolean available() { return Loader.isModLoaded("offscreencleanroom"); }

    /**
     * Retrieves the current active offscreen window instance, if any.
     *
     * @return current window object, or {@code null}
     */
    static Object currentWindow() { return available() ? Backend.current() : null; }

    /**
     * Checks whether a modular screen is currently hosted in an offscreen window.
     *
     * @param screen modular screen
     * @return {@code true} if hosted, {@code false} otherwise
     */
    static boolean isHosted(ModularScreen screen) { return available() && Backend.owners.containsKey(screen); }

    /**
     * Determines whether a pressed key should trigger closing the screen.
     *
     * @param screen modular screen
     * @param key    LWJGL keyboard key code
     * @return {@code true} if close key is matched, {@code false} otherwise
     */
    static boolean isCloseKey(ModularScreen screen, int key) {
        return key == org.lwjgl.input.Keyboard.KEY_ESCAPE || isHosted(screen) &&
                Minecraft.getMinecraft().gameSettings.keyBindInventory.isActiveAndMatches(key);
    }

    /**
     * Schedules an action to run on the appropriate window thread or task queue.
     *
     * @param action task to run
     */
    static void schedule(Runnable action) {
        Object destination = currentWindow();
        if (destination == null) Minecraft.getMinecraft().addScheduledTask(action);
        else ClientProxy.afterScreenClosed(() -> Backend.run(destination, action));
    }

    /** Prepares client state before opening a viewer screen by closing existing screens. */
    static void prepareOpen() {
        if (currentWindow() != null) return;
        Minecraft client = Minecraft.getMinecraft();
        client.player.closeScreen();
        client.displayGuiScreen(null);
    }

    /**
     * Retrieves the appropriate scaled resolution for the current window or viewport.
     *
     * @return scaled resolution instance
     */
    static ScaledResolution resolution() {
        return currentWindow() == null ? new ScaledResolution(Minecraft.getMinecraft()) : Backend.resolution();
    }

    /**
     * Opens a modular screen either via vanilla client GUI or hosted offscreen window.
     *
     * @param screen modular screen to open
     */
    static void open(ModularScreen screen) {
        if (currentWindow() == null) ClientGUI.open(screen);
        else Backend.open(screen);
    }

    /**
     * Closes a modular screen, accounting for offscreen window hosting.
     *
     * @param screen modular screen to close
     */
    static void close(ModularScreen screen) {
        if (!isHosted(screen)) { screen.close(); return; }
        ClientProxy.afterScreenClosed(() -> Backend.close(screen));
    }

    /**
     * Creates a fallback return action for a hosted screen.
     *
     * @param screen   modular screen
     * @param fallback fallback runnable
     * @return return runnable action
     */
    static Runnable returnTo(ModularScreen screen, Runnable fallback) {
        if (!isHosted(screen)) return fallback;
        return () -> ClientProxy.afterScreenClosed(() -> Backend.returnTo(screen));
    }

    /**
     * Closes all active offscreen hosted windows.
     *
     * @return {@code true} if handled, {@code false} otherwise
     */
    static boolean closeAll() {
        Object destination = currentWindow();
        if (destination == null) return false;
        ClientProxy.afterScreenClosed(() -> Backend.closeWindow(destination));
        return true;
    }

    /**
     * Detaches a viewer screen into its own separate offscreen window.
     *
     * @param opener opener runnable action
     */
    static void detach(Runnable opener) {
        if (!available()) { opener.run(); return; }
        ClientProxy.afterScreenClosed(() -> Backend.detach(opener));
    }

    /**
     * Adds an offscreen detach window button to a modular panel.
     *
     * @param panel  modular panel
     * @param opener opener runnable action
     * @param x      x position
     * @param y      y position
     */
    static void addButton(ModularPanel panel, Runnable opener, int x, int y) {
        if (!available()) return;
        panel.child(StructureStyle.button(() -> "\u2197", () -> detach(opener)).pos(x, y).size(18, 18)
                .tooltip(tooltip -> tooltip.addLine(I18n.format("avogadro.offscreen.open")))
                .setEnabledIf(widget -> currentWindow() == null));
    }

    /** Backend implementation handling offscreen window interaction and mapping. */
    private static final class Backend {
        private static final WeakHashMap<ModularScreen, WeakReference<com.sbancuz.offscreen.window.Window>> owners = new WeakHashMap<>();
        private static com.sbancuz.offscreen.window.Window requested;

        /**
         * Retrieves the active offscreen window.
         *
         * @return offscreen window instance, or {@code null}
         */
        private static com.sbancuz.offscreen.window.Window current() {
            if (requested != null) return requested;
            var owner = owners.get(ModularScreen.getCurrent());
            if (owner != null && owner.get() != null) return owner.get();
            var screen = Minecraft.getMinecraft().currentScreen;
            for (var window : com.sbancuz.offscreen.api.OffscreenAPI.getWindows()) {
                if (window.getCurrentUI() != null && window.getCurrentUI().getGuiScreen() == screen) return window;
            }
            return null;
        }

        /**
         * Executes an action within the context of a specific window destination.
         *
         * @param destination window destination object
         * @param action      action runnable
         */
        private static void run(Object destination, Runnable action) {
            var window = (com.sbancuz.offscreen.window.Window) destination;
            if (!window.isOpen() || Minecraft.getMinecraft().player == null) return;
            var previous = requested;
            requested = window;
            try { action.run(); }
            catch (RuntimeException | Error failure) {
                if (window.getCurrentUI() == null) window.destroy();
                throw failure;
            } finally { requested = previous; }
        }

        /**
         * Detaches an action into a new offscreen window.
         *
         * @param opener opener runnable action
         */
        private static void detach(Runnable opener) {
            if (Minecraft.getMinecraft().player == null) return;
            var window = new com.sbancuz.offscreen.window.Window("Avogadro");
            run(window, opener);
        }

        /**
         * Computes the scaled resolution for the current offscreen window dimensions.
         *
         * @return scaled resolution instance
         */
        private static ScaledResolution resolution() {
            Minecraft mc = Minecraft.getMinecraft();
            int width = mc.displayWidth, height = mc.displayHeight;
            try {
                mc.displayWidth = current().getPixelWidth();
                mc.displayHeight = current().getPixelHeight();
                return new ScaledResolution(mc);
            } finally {
                mc.displayWidth = width;
                mc.displayHeight = height;
            }
        }

        /**
         * Opens a modular screen inside the active offscreen window.
         *
         * @param screen modular screen
         */
        private static void open(ModularScreen screen) {
            var window = current();
            screen.getContext().setSettings(new UISettings());
            new GuiScreenWrapper(screen);
            var delegate = com.sbancuz.offscreen.api.UIRegistry.resolve(screen);
            if (delegate == null) throw new IllegalStateException("Offscreen cannot host ModularUI screens");
            owners.put(screen, new WeakReference<>(window));
            screen.openParentOnClose(false);
            window.push(new Hosted(delegate, screen));
        }

        /**
         * Closes a hosted modular screen in its offscreen window.
         *
         * @param screen modular screen
         */
        private static void close(ModularScreen screen) {
            var owner = owners.get(screen);
            var window = owner == null ? null : owner.get();
            if (window == null || !window.isOpen()) return;
            if (!(window.getCurrentUI() instanceof Hosted hosted) || hosted.screen != screen) return;
            if (!window.pop()) window.destroy();
        }

        /**
         * Returns to a specific modular screen within the window stack.
         *
         * @param screen modular screen
         */
        private static void returnTo(ModularScreen screen) {
            var owner = owners.get(screen);
            var window = owner == null ? null : owner.get();
            if (window == null || !window.isOpen()) return;
            while (!(window.getCurrentUI() instanceof Hosted hosted && hosted.screen == screen)) {
                if (!window.pop()) return;
            }
        }

        /**
         * Closes a specific offscreen window.
         *
         * @param window window object
         */
        private static void closeWindow(Object window) {
            com.sbancuz.offscreen.api.OffscreenAPI.close((com.sbancuz.offscreen.window.Window) window);
        }
    }

    /** Host UI implementation wrapping ModularUI screens for offscreen rendering and frame scoping. */
    private static final class Hosted implements com.sbancuz.offscreen.api.HostUI {
        private final com.sbancuz.offscreen.api.HostUI delegate;
        private final ModularScreen screen;
        private final ViewerFrameScope frames = new ViewerFrameScope();
        private final com.sbancuz.offscreen.scope.Scope scope;
        private int scopeDepth;

        /**
         * Constructs a new hosted offscreen UI wrapper.
         *
         * @param delegate delegate host UI
         * @param screen   modular screen
         */
        private Hosted(com.sbancuz.offscreen.api.HostUI delegate, ModularScreen screen) {
            this.delegate = delegate;
            this.screen = screen;
            this.scope = new com.sbancuz.offscreen.scope.Scope() {
                @Override public void enter() {
                    frames.enter();
                    try {
                        if (scopeDepth == 0) delegate.scope().enter();
                        scopeDepth++;
                    }
                    catch (RuntimeException | Error failure) { frames.restore(); throw failure; }
                }
                @Override public void restore() {
                    try { if (--scopeDepth == 0) delegate.scope().restore(); }
                    finally { frames.restore(); }
                }
            };
        }

        /**
         * Retrieves the rendering scope associated with this host UI.
         *
         * @return scope instance
         */
        @Override public com.sbancuz.offscreen.scope.Scope scope() { return scope; }

        /**
         * Retrieves the underlying Minecraft GUI screen.
         *
         * @return GUI screen instance
         */
        @Override public net.minecraft.client.gui.GuiScreen getGuiScreen() { return delegate.getGuiScreen(); }

        /**
         * Handles screen resize events.
         *
         * @param width  new screen width
         * @param height new screen height
         */
        @Override public void onResize(int width, int height) { delegate.onResize(width, height); }

        /** Updates UI state and logic ticks. */
        @Override public void update() { delegate.update(); }

        /**
         * Processes raw input frame events.
         1         *
         * @param event        frame input event
         * @param partialTicks partial tick time interpolation factor
         */
        @Override public void onInput(com.sbancuz.offscreen.window.input.FrameEvent event, float partialTicks) {
            delegate.onInput(event, partialTicks);
        }

        /**
         * Renders the host UI frame within scoped boundaries and frame timing controls.
         *
         * @param mc           Minecraft client instance
         * @param x            x screen position
         * @param y            y screen position
         * @param partialTicks partial tick time interpolation factor
         * @param now          current timestamp
         */
        @Override public void draw(Minecraft mc, int x, int y, float partialTicks, long now) {
            frames.beginFrame();
            try { delegate.draw(mc, x, y, partialTicks, now); }
            finally { frames.endFrame(); }
        }

        /** Disposes host UI resources, scopes, and unregisters the screen ownership mapping. */
        @Override public void dispose() {
            scope.enter();
            try { delegate.dispose(); }
            finally {
                try { scope.restore(); }
                finally { frames.dispose(); Backend.owners.remove(screen); }
            }
        }
    }
}
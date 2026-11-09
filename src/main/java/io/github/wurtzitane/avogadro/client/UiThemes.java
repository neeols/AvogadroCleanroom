package io.github.wurtzitane.avogadro.client;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.ToIntFunction;
import java.util.function.Supplier;

import com.cleanroommc.modularui.api.widget.IWidget;

import io.github.wurtzitane.avogadro.Avogadro;
import io.github.wurtzitane.avogadro.Config;
import io.github.wurtzitane.avogadro.theme.ThemeDefinition;
import io.github.wurtzitane.avogadro.theme.ThemeStore;

import net.minecraftforge.client.event.RenderTooltipEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

/**
 * Client theme selection and scoped color lookup. A widget's menu and path travel with its draw call,
 * including detached windows. Drawing one menu cannot leave its overrides active in another menu.
 */
@Mod.EventBusSubscriber(modid = Avogadro.MODID, value = Side.CLIENT)
public final class UiThemes {
    private static ThemeStore store;
    private static volatile ThemeDefinition.Compiled active;
    private static ThemeDefinition defaults;
    private static volatile long revision;
    private static final ThreadLocal<Context> CURRENT = ThreadLocal.withInitial(() -> new Context("shared", "root"));
    private static final Map<IWidget, Context> WIDGETS = new WeakHashMap<>();
    private static final Map<com.cleanroommc.modularui.screen.ModularScreen, Supplier<String>> MENUS = new WeakHashMap<>();
    private static ToIntFunction<Sample> probe;
    private static boolean probeHover;
    private static long probeRevision;
    private static final ThreadLocal<Map<Integer,Integer>> ORIGINALS = ThreadLocal.withInitial(HashMap::new);

    /** A semantic draw address, used by native preview capture and the standalone editor. */
    public record Sample(String screen, String element, String role, int argb) {}

    /** Immutable theme selection retained by an export, so changing menus cannot recolor a running job. */
    public record Snapshot(ThemeDefinition.Compiled theme, long revision) {}

    /** Restores the previous color context. Close this in a try-with-resources block. */
    public interface Scope extends AutoCloseable {
        @Override void close();
    }

    private static final class Context {
        final String screen, element;
        final Map<String, Integer> colors = new HashMap<>();
        final Snapshot snapshot;
        boolean recordColors = true;
        boolean tooltip;
        final Map<Integer,String> textRoles = new HashMap<>();
        String lastTextRole;
        int lastTextColor;
        long revision = -1;
        Context(String screen, String element) { this(screen, element, null); }
        Context(String screen, String element, Snapshot snapshot) { this.screen = screen; this.element = element; this.snapshot = snapshot; }
    }

    private UiThemes() {}

    /**
     * Initializes user theme files after the common configuration is loaded.
     *
     * @param directory instance config/avogadro folder
     */
    public static void initialize(Path directory) {
        try {
            store = new ThemeStore(directory);
            reload();
        } catch (IOException | RuntimeException failure) {
            Avogadro.LOGGER.error("Cannot initialize Avogadro themes in {}", directory, failure);
        }
    }

    /** Rescans the themes folder and applies the current Mod Options selection without resetting cameras. */
    public static void reload() {
        if (store == null) return;
        store.reload();
        store.problems().forEach(problem -> Avogadro.LOGGER.warn("Theme: {}", problem));
        defaults = store.defaults();
        ThemeStore.Entry selected = store.entries().get(Config.UI_THEME.get());
        if (selected == null) selected = store.entries().get("default_theme");
        active = selected.compiled();
        revision++;
        // These contain painted colors, rather than just camera or atom positions.
        PeriodicTableArtwork.clear();
        ElementCard.clearThemeLayouts();
    }

    /** Returns discovered, validated themes for the native Mod Options selector. */
    public static Map<String, ThemeStore.Entry> available() { return available(ThemeStore.Order.RAINBOW, true); }

    /** Returns the current catalog in a picker-local order without changing the selected theme. */
    public static Map<String, ThemeStore.Entry> available(ThemeStore.Order order, boolean pinDefaults) {
        return store == null ? Map.of() : store.ordered(order, pinDefaults);
    }

    /** Returns the user theme folder, or null before client configuration has loaded. */
    public static Path directory() { return store == null ? null : store.themesDirectory(); }

    /** Returns the presentation revision used to invalidate caches containing colors. */
    public static long revision() { return revision; }

    /**
     * Resolves a semantic color at the current menu/element address.
     *
     * @param role semantic color name
     * @param fallback legacy ARGB, used before initialization or for extension roles
     * @return current theme color
     */
    public static int color(String role, int fallback) {
        if (active == null) {
            try {
                defaults = ThemeStore.bundled("default_theme.json");
                active = defaults.compile(defaults);
            } catch (IOException failure) {
                return fallback;
            }
        }
        Context context = CURRENT.get();
        long selectedRevision = context.snapshot == null ? revision : context.snapshot.revision();
        ThemeDefinition.Compiled selectedTheme = context.snapshot == null ? active : context.snapshot.theme();
        if (context.revision != selectedRevision) {
            context.colors.clear();
            context.revision = selectedRevision;
        }
        int value = context.colors.computeIfAbsent(role,
                key -> selectedTheme.color(key, context.screen, context.element, fallback));
        boolean recording = probe != null && context.recordColors;
        int painted = recording ? probe.applyAsInt(new Sample(context.screen, context.element, role, value)) : value;
        if (recording) ORIGINALS.get().put(painted, value);
        if (role.startsWith("text.") || role.contains(".text") || role.endsWith("Text")) {
            context.textRoles.put(painted, role);
            context.lastTextRole = role;
            context.lastTextColor = painted;
        }
        return painted;
    }

    /**
     * Opens a menu/element color context. The actual renderer remains unchanged.
     *
     * @param screen menu identifier
     * @param element stable control path
     * @return restoration scope
     */
    public static Scope scope(String screen, String element) {
        Context previous = CURRENT.get();
        CURRENT.set(new Context(screen, element));
        return () -> CURRENT.set(previous);
    }

    /** Scopes a named part of the current control, preserving the export snapshot when present. */
    public static Scope element(String name) {
        Context previous = CURRENT.get();
        Context child = new Context(previous.screen, previous.element + "/" + name, previous.snapshot);
        child.recordColors = previous.recordColors;
        child.tooltip = previous.tooltip;
        CURRENT.set(child);
        return () -> CURRENT.set(previous);
    }

    /** Uses tooltip ink and chrome within the current Avogadro menu; unrelated tooltips retain their own colors. */
    public static Scope tooltip() {
        if (!isScoped()) return () -> {};
        Context previous = CURRENT.get();
        Context child = new Context(previous.screen, previous.element + "/tooltip", previous.snapshot);
        child.recordColors = previous.recordColors;
        child.tooltip = true;
        CURRENT.set(child);
        return () -> CURRENT.set(previous);
    }

    static boolean isTooltip() { return CURRENT.get().tooltip; }

    /** Applies the theme through the shared Forge event used by native and ModularUI tooltips. */
    @SubscribeEvent
    public static void tooltipColors(RenderTooltipEvent.Color event) {
        if (!isScoped()) return;
        event.setBackground(color("tooltip.background", event.getBackground()));
        event.setBorderStart(color("tooltip.border.top", event.getBorderStart()));
        event.setBorderEnd(color("tooltip.border.bottom", event.getBorderEnd()));
    }

    /** Returns the semantic role carried by a resolved text color. */
    static String textRole(int color) {
        Context context = CURRENT.get();
        if (context.lastTextRole != null && context.lastTextColor == color) return context.lastTextRole;
        return context.textRoles.getOrDefault(color, context.tooltip ? "tooltip.text" : "text.primary");
    }

    /** Gives labels without an explicit semantic color the theme's main text color. */
    static int textColor(int color) {
        if (!isScoped()) return color;
        Context context = CURRENT.get();
        return context.textRoles.containsKey(color) ? color : color(context.tooltip ? "tooltip.text" : "text.primary", color);
    }

    /** Configuration labels use the same flat text as the molecule viewers. */
    static boolean textShadow(boolean requested) {
        String screen = CURRENT.get().screen;
        return requested && !screen.startsWith("options") && !screen.equals("theme_selector");
    }

    /** Returns an unmodified color for contrast calculation during semantic-mask capture. */
    public static int originalColor(int color) { return probe == null ? color : ORIGINALS.get().getOrDefault(color, color); }

    /** Revision for painted atomic detail buffers, including native preview-capture passes. */
    public static long paintRevision() {
        Context context = CURRENT.get();
        return (context.snapshot == null ? revision : context.snapshot.revision()) ^ (probeRevision << 32);
    }

    /** Takes a thread-safe immutable color snapshot for a new export. */
    public static Snapshot snapshot() {
        color("window.background", 0xFFF4F1E8);
        return new Snapshot(active, revision);
    }

    /** Uses an export's retained theme on either the capture thread or an artwork worker. */
    public static Scope scope(Snapshot snapshot, String screen, String element) {
        Context previous = CURRENT.get();
        CURRENT.set(new Context(screen, element, snapshot));
        return () -> CURRENT.set(previous);
    }

    /** Draws a candidate theme's color sample without applying or recording it as the selected theme. */
    static Scope preview(ThemeDefinition.Compiled theme) {
        Context previous = CURRENT.get();
        Context sample = new Context("polymer_examiner", "root", new Snapshot(theme, revision));
        sample.recordColors = false;
        CURRENT.set(sample);
        return () -> CURRENT.set(previous);
    }

    /**
     * Scopes one native ModularUI widget. Other mods' widgets are deliberately left alone.
     *
     * @param widget widget being drawn
     * @return restoration scope
     */
    public static Scope widget(IWidget widget) {
        if (!"avogadro".equals(widget.getScreen().getOwner())) return () -> {};
        Context previous = CURRENT.get();
        String screen = MENUS.containsKey(widget.getScreen()) ? MENUS.get(widget.getScreen()).get() : widget.getScreen().getName();
        Context context = WIDGETS.get(widget);
        if (context == null || !context.screen.equals(screen)) {
            context = new Context(screen, path(widget));
            WIDGETS.put(widget, context);
        }
        CURRENT.set(context);
        if (widget instanceof com.cleanroommc.modularui.widgets.textfield.BaseTextFieldWidget<?> field) {
            field.setTextColor(color("input.text", StructureStyle.INK));
            field.setMarkedColor(color("input.selection", 0xFFA9C6D8));
            field.hintColor(color("text.muted", StructureStyle.MUTED));
        }
        return () -> CURRENT.set(previous);
    }

    /** Names logical pages while retaining one shared production viewer and widget tree. */
    static void menu(com.cleanroommc.modularui.screen.ModularScreen screen, Supplier<String> name) {
        MENUS.put(screen, name);
    }

    /** Returns whether the current draw belongs to an Avogadro menu. */
    public static boolean isScoped() { return !CURRENT.get().screen.equals("shared"); }

    private static String path(IWidget widget) {
        if (widget == widget.getPanel()) return "root";
        String name = widget.getName();
        if (name == null || name.isBlank())
            name = (widget.getClass().getSimpleName().isEmpty() ? "widget" : widget.getClass().getSimpleName())
                    .replaceAll("[^a-zA-Z0-9_-]", "-").toLowerCase(java.util.Locale.ROOT)
                    + "-" + widget.getParent().getChildren().indexOf(widget);
        return path(widget.getParent()) + "/" + name;
    }

    /**
     * Temporarily records semantic-color pixels for release preview assets. Ordinary clients never enable it.
     *
     * @param recorder maps a resolved color/address to a capture color; null disables recording
     * @param hover whether to show every button's hover state in this capture
     */
    public static void previewProbe(ToIntFunction<Sample> recorder, boolean hover) {
        probe = recorder;
        probeHover = hover;
        probeRevision++;
        ORIGINALS.get().clear();
    }

    /** Returns real hover state, or the requested hover state during preview-asset capture. */
    public static boolean hovered(boolean actual) { return probe == null || !CURRENT.get().recordColors ? actual : probeHover; }
}

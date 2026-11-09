package io.github.wurtzitane.avogadro;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;

/**
 * Mod configuration management utility handling configuration loading, synchronization, migration, and value wrappers.
 */
public final class Config {

    /** Forge configuration instance. */
    public static Configuration configuration;

    // Category name constants for configuration sections
    public static final String TOOLTIPS = "tooltips";
    public static final String APPEARANCE = "appearance";
    public static final String RECIPE_BROWSER = "recipebrowser";
    public static final String ATOMS = "atoms";
    public static final String WORLD_HUD = "worldhud";
    public static final String PERIODIC_TABLE = "periodictable";
    public static final String ELEMENTS = "elements";
    public static final String EXPORTS = "exports";

    /** Unmodifiable list of all configuration category names. */
    public static final List<String> CATEGORIES = List.of(TOOLTIPS, APPEARANCE, RECIPE_BROWSER, WORLD_HUD, ATOMS,
            EXPORTS, PERIODIC_TABLE, ELEMENTS);

    // --- Export & Performance Configuration Values ---
    public static final Value<ExportPerformance> EXPORT_PERFORMANCE = new Value<>(ExportPerformance.SMOOTH_GAMEPLAY);

    // --- Periodic Table Configuration Values ---
    public static final Value<Boolean> PERIODIC_TABLE_GLOWING = new Value<>(false);
    public static final Value<Boolean> PERIODIC_TABLE_CRAFTING = new Value<>(true);

    // --- Element & Isotope Configuration Values ---
    public static final Value<Boolean> AUTO_LOAD_ELEMENTS = new Value<>(false);
    public static final Value<Boolean> AUTO_LOAD_ISOTOPES = new Value<>(false);

    // --- Atom & 3D Export Rendering Configuration Values ---
    public static final Value<Boolean> ATOM_CLOUD = new Value<>(true);
    public static final Value<Boolean> ATOM_ORBITS = new Value<>(true);
    public static final Value<Boolean> ATOM_NUCLEUS = new Value<>(true);
    public static final Value<Boolean> ATOM_ELECTRONS = new Value<>(true);
    public static final Value<Double> ATOM_REVEAL_ZOOM = new Value<>(1.5);
    public static final Value<Boolean> ATOM_DETAILS = new Value<>(true);
    public static final Value<Boolean> PREDICT_NEUTRAL_ELECTRON_CONFIGS = new Value<>(false);
    public static final Value<Integer> ATOM_WIDTH = new Value<>(1000);
    public static final Value<Integer> ATOM_HEIGHT = new Value<>(640);
    public static final Value<Integer> ATOM_FPS = new Value<>(24);
    public static final Value<Integer> VIEWER_FPS = new Value<>(0);
    public static final Value<Double> ATOM_TEXT_SCALE = new Value<>(1.0);

    // --- Tooltip Configuration Values ---
    public static final Value<Boolean> ENABLE_TOOLTIPS = new Value<>(true);
    public static final Value<Integer> TOOLTIP_SIZE = new Value<>(64);
    /** Whether the atomic-tooltip key is held down or toggles the preview until the menu closes. */
    public static final Value<HudMode> TOOLTIP_ATOM_MODE = new Value<>(HudMode.HOLD);

    // --- Visual Appearance & Rendering Configuration Values ---
    public static final Value<Double> SPIN_SECONDS = new Value<>(6.0);
    public static final Value<RotationDirection> ROTATION_DIRECTION = new Value<>(RotationDirection.FORWARD);
    public static final Value<Boolean> JEI_CATEGORY = new Value<>(true);
    public static final Value<Boolean> CUBIC_ATOMS = new Value<>(false);
    public static final Value<RenderingMode> RENDERING_MODE = new Value<>(RenderingMode.LIVE);
    public static final Value<Double> TEXT_SCALE = new Value<>(1.0);
    /** Filename of the selected UI theme, without its JSON extension. */
    public static final Value<String> UI_THEME = new Value<>("default_theme");

    // --- World HUD Configuration Values ---
    public static final Value<Boolean> WORLD_HUD_ENABLED = new Value<>(true);
    public static final Value<HudMode> WORLD_HUD_MODE = new Value<>(HudMode.HOLD);
    public static final Value<HudProvider> WORLD_HUD_PROVIDER = new Value<>(HudProvider.AUTO);

    // --- Creative Inventory Sorting & Tabs Configuration Values ---
    public static final Value<CreativeSort> CREATIVE_SORT = new Value<>(CreativeSort.NAME);
    public static final Value<Boolean> SPLIT_CREATIVE_TABS = new Value<>(true);
    public static final Value<Boolean> SPLIT_CRYSTAL_TAB = new Value<>(true);
    public static final Value<Boolean> SPLIT_POLYMER_TAB = new Value<>(true);
    public static final Value<CreativeSort> ELEMENT_SORT = new Value<>(CreativeSort.ATOMIC_NUMBER);

    /** Enum representing creative inventory sort modes. */
    public enum CreativeSort {
        NAME, FORMULA, ATOMIC_NUMBER, ID, REGISTRATION
    }

    /** Enum representing world HUD operational modes. */
    public enum HudMode {
        HOLD, TOGGLE
    }

    /** Enum representing world HUD providers. */
    public enum HudProvider {
        AUTO, PROBE, WAILA
    }

    /** Enum representing rendering modes. */
    public enum RenderingMode {
        LIVE, PRERENDERED
    }

    /** Enum representing export performance modes. */
    public enum ExportPerformance {
        SMOOTH_GAMEPLAY, FASTEST_EXPORT
    }

    /** Enum representing automatic rotation directions. */
    public enum RotationDirection {
        FORWARD(1), REVERSE(-1);

        /** Rotation direction sign integer. */
        private final int sign;

        /**
         * Constructs a rotation direction enum constant with a sign.
         *
         * @param sign sign integer
         */
        RotationDirection(int sign) {
            this.sign = sign;
        }

        /**
         * Returns the rotation direction sign integer.
         *
         * @return sign integer
         */
        public int sign() {
            return sign;
        }
    }

    private Config() {}

    /**
     * Loads and initializes the configuration from a configuration file, migrating obsolete properties and applying
     * system overrides.
     *
     * @param file configuration file instance
     */
    public static void load(File file) {
        configuration = new Configuration(file);
        configuration.load();

        // Remove obsolete tooltip configuration properties
        for (String obsolete : List.of("diagramSize", "diagramActivation", "diagramHint")) {
            configuration.getCategory(TOOLTIPS).remove(obsolete);
        }

        // Migrate legacy properties to their proper categories
        migrate("enableTooltips", TOOLTIPS);
        migrate("tooltipSize", TOOLTIPS);
        migrate("spinSeconds", APPEARANCE);
        migrate("cubicAtoms", APPEARANCE);
        migrate("jeiCategory", RECIPE_BROWSER);

        // Clean up empty general categories
        if (configuration.hasCategory("general") && configuration.getCategory("general").isEmpty()) {
            configuration.removeCategory(configuration.getCategory("general"));
        }

        // Set language translation keys for all configuration categories
        for (String category : CATEGORIES) {
            configuration.setCategoryLanguageKey(category, "avogadro.configuration.category." + category);
        }

        // Sync configuration values
        sync();

        // Apply system property overrides for neutral electron configuration predictions if present
        String predictionOverride = System.getProperty("avogadro.predictNeutralElectronConfigs");
        if ("true".equalsIgnoreCase(predictionOverride) || "false".equalsIgnoreCase(predictionOverride)) {
            boolean enabled = Boolean.parseBoolean(predictionOverride);
            configuration.getCategory(ATOMS).get("predictNeutralElectronConfigs").set(enabled);
            PREDICT_NEUTRAL_ELECTRON_CONFIGS.value = enabled;
        }
    }

    /** Synchronizes configuration property values into their respective value wrappers and saves changes. */
    public static void sync() {
        Property selectedTheme = configuration.get(APPEARANCE, "theme", "default_theme",
                "UI theme. Bundled themes and JSON files in config/avogadro/themes are listed in Mod Options.")
                .setLanguageKey(languageKey("theme"));
        UI_THEME.value = selectedTheme.getString();
        // --- Exports & Performance ---
        EXPORT_PERFORMANCE.value = option(EXPORTS, "performanceMode", ExportPerformance.SMOOTH_GAMEPLAY,
                ExportPerformance.class,
                "Smooth Gameplay spreads export capture across frames. Fastest Export prioritizes completion speed. Quality and frame count are identical; changes apply on the next render frame.");

        // --- Elements & Isotopes ---
        AUTO_LOAD_ELEMENTS.value = configuration.get(ELEMENTS, "autoLoadElements", false,
                "Add one creative and HEI sample for each of the 118 elements using its reference isotope. The periodic-table reference always remains available. Requires restart.")
                .setLanguageKey(languageKey("elements.autoLoadElements")).setRequiresMcRestart(true).getBoolean();

        AUTO_LOAD_ISOTOPES.value = configuration.get(ELEMENTS, "autoLoadIsotopes", false,
                "Add creative and HEI samples for all 3339 isotopes in the bundled NUBASE2020 catalog. Independent of autoLoadElements. Requires restart.")
                .setLanguageKey(languageKey("elements.autoLoadIsotopes")).setRequiresMcRestart(true).getBoolean();

        // --- Periodic Table ---
        PERIODIC_TABLE_GLOWING.value = bool(PERIODIC_TABLE, "glowingPainting", false,
                "Render wall-mounted periodic tables at full brightness instead of using world lighting. Does not emit light.");

        PERIODIC_TABLE_CRAFTING.value = configuration
                .get(PERIODIC_TABLE, "enableCrafting", true,
                        "Enable the periodic-table recipe and show the poster in creative tabs and HEI. Existing posters remain usable when disabled. Requires restart.")
                .setLanguageKey(languageKey("periodicTable.enableCrafting")).setRequiresMcRestart(true).getBoolean();

        // --- Atoms & 3D Exports ---
        double revealZoom = configuration.get(ATOMS, "atomRevealZoom", 1.5,
                "Molecule zoom at which solid atom balls begin fading to reveal atomic components. Fully revealed one zoom unit later. Normal zoom remains opaque.",
                1.0, 16.0).setLanguageKey(languageKey("atomRevealZoom")).getDouble();
        ATOM_REVEAL_ZOOM.value = Double.isFinite(revealZoom) ? Math.clamp(revealZoom, 1.0, 16.0) : 1.5;

        ATOM_CLOUD.value = bool(ATOMS, "atomCloud", true,
                "Show the translucent electron envelope in the atom examiner and 3D exports.");
        ATOM_ORBITS.value = bool(ATOMS, "atomOrbits", true, "Show the schematic electron paths.");
        ATOM_NUCLEUS.value = bool(ATOMS, "atomNucleus", true, "Show protons and neutrons.");
        ATOM_ELECTRONS.value = bool(ATOMS, "atomElectrons", true, "Show moving electrons.");
        ATOM_DETAILS.value = bool(ATOMS, "atomDetails", true,
                "Include the element card in 3D exports unless --details overrides it.");

        PREDICT_NEUTRAL_ELECTRON_CONFIGS.value = bool(ATOMS, "predictNeutralElectronConfigs", false,
                "Show predicted, calculated and authored neutral electron configurations. Unknown configurations display UNKNOWN when disabled.");

        ATOM_WIDTH.value = configuration.getInt("atomWidth", ATOMS, 1000, 320, 4096, "Default 3D export width.",
                languageKey("atomWidth"));
        ATOM_HEIGHT.value = configuration.getInt("atomHeight", ATOMS, 640, 240, 4096, "Default 3D export height.",
                languageKey("atomHeight"));
        ATOM_FPS.value = configuration.getInt("atomFps", ATOMS, 24, 1, Integer.MAX_VALUE,
                "Default 3D export frame rate. MP4 accepts positive integers within the export frame limit; GIF is capped at 50 fps.",
                languageKey("atomFps"));

        VIEWER_FPS.value = configuration.getInt("viewerFps", ATOMS, 0, 0, 10000,
                "Avogadro menu frame limit. Zero uses the active monitor's refresh rate; exports and Minecraft's saved frame limit are unchanged.",
                languageKey("viewerFps"));

        double atomScale = configuration
                .get(ATOMS, "atomTextScale", 1.0, "3D export text size; --text-scale overrides this.", 0.75, 1.5)
                .setLanguageKey(languageKey("atomTextScale")).getDouble();
        ATOM_TEXT_SCALE.value = Double.isFinite(atomScale) ? Math.clamp(atomScale, 0.75, 1.5) : 1.0;

        // --- Tooltips ---
        ENABLE_TOOLTIPS.value = bool(TOOLTIPS, "enableTooltips", true,
                "Show molecule models in item, fluid-container and direct-fluid tooltips. Independent of the HEI page and world HUDs.");

        TOOLTIP_SIZE.value = configuration.getInt("tooltipSize", TOOLTIPS, 64, 16, 128,
                "Molecule box size in GUI pixels.", languageKey("tooltipSize"));
        TOOLTIP_ATOM_MODE.value = option(TOOLTIPS, "atomPreviewMode", HudMode.HOLD, HudMode.class,
                "Hold or toggle the Atomic tooltip preview key (Left Ctrl by default). Only single-atom element tooltips change; the cloud keeps the solid ball's size and pose. Toggle resets when the menu closes or loses focus. Rebind the key in Controls > Avogadro.");

        // --- Appearance & Styling ---
        double spinSeconds = configuration
                .get(APPEARANCE, "spinSeconds", 6.0, "Seconds per complete rotation.", 0.5, 60.0)
                .setLanguageKey(languageKey("spinSeconds")).getDouble();
        SPIN_SECONDS.value = Double.isFinite(spinSeconds) ? Math.clamp(spinSeconds, 0.5, 60.0) : 6.0;

        ROTATION_DIRECTION.value = option(APPEARANCE, "rotationDirection", RotationDirection.FORWARD,
                RotationDirection.class, "Direction of automatic molecule rotation.");

        JEI_CATEGORY.value = bool(RECIPE_BROWSER, "jeiCategory", true,
                "Show the Molecules page in HEI. Independent of item/fluid tooltips and world HUDs.");

        Property splitTabs = configuration.get(APPEARANCE, "splitCreativeTabs", true,
                "Separate elemental samples from compounds in the creative inventory. Disable to combine both in Molecules. Requires restart.")
                .setLanguageKey(languageKey("splitCreativeTabs")).setRequiresMcRestart(true);
        SPLIT_CREATIVE_TABS.value = splitTabs.getBoolean();

        SPLIT_CRYSTAL_TAB.value = configuration
                .get(APPEARANCE, "separateCrystalTab", true,
                        "Show crystalline samples in their own creative tab. Requires restart.")
                .setLanguageKey(languageKey("separateCrystalTab")).setRequiresMcRestart(true).getBoolean();

        SPLIT_POLYMER_TAB.value = configuration
                .get(APPEARANCE, "separatePolymerTab", true,
                        "Show polymer samples in their own creative tab. Requires restart.")
                .setLanguageKey(languageKey("separatePolymerTab")).setRequiresMcRestart(true).getBoolean();

        CREATIVE_SORT.value = option(APPEARANCE, "creativeSort", CreativeSort.NAME, CreativeSort.class,
                "Molecule creative tab order: translated NAME, FORMULA, configured ATOMIC_NUMBER, stable ID, or original REGISTRATION order.");

        ELEMENT_SORT.value = option(APPEARANCE, "elementSort", CreativeSort.ATOMIC_NUMBER, CreativeSort.class,
                "Elements tab order. ATOMIC_NUMBER uses the configured proton count; definitions without atomic data sort last. Independent of molecule tab sorting.");

        CUBIC_ATOMS.value = bool(APPEARANCE, "cubicAtoms", false, "Draw cubic atoms and square bonds.");

        RENDERING_MODE.value = option(APPEARANCE, "renderingMode", RenderingMode.LIVE, RenderingMode.class,
                "LIVE keeps camera controls. PRERENDERED caches full-geometry rotation frames with a fixed camera; HEI camera controls are disabled. Exports always render full geometry.");

        Property textScale = configuration.get(APPEARANCE, "textScale", 1.0,
                "Scale Avogadro's own name and formula labels in the Molecules category and media exports.", 0.5, 3.0)
                .setLanguageKey(languageKey("textScale"));
        double scale = textScale.getDouble();
        TEXT_SCALE.value = Double.isFinite(scale) ? Math.clamp(scale, 0.5, 3.0) : 1.0;
        if (Double.compare(scale, TEXT_SCALE.value) != 0)
            textScale.set(TEXT_SCALE.value);

        // --- World HUD ---
        WORLD_HUD_ENABLED.value = bool(WORLD_HUD, "enabled", true,
                "Show matching molecule models below native world-HUD text. Independent of item/fluid tooltips and the HEI page.");
        WORLD_HUD_MODE.value = option(WORLD_HUD, "mode", HudMode.HOLD, HudMode.class, "World HUD mode.");
        WORLD_HUD_PROVIDER.value = option(WORLD_HUD, "provider", HudProvider.AUTO, HudProvider.class,
                "World HUD provider.");

        // --- Save Changes ---
        if (configuration.hasChanged()) {
            configuration.save();
        }
    }

    /**
     * Migrates a property from the general configuration category to a target category.
     *
     * @param name property name string
     * @param category target category name string
     */
    private static void migrate(String name, String category) {
        if (!configuration.hasKey("general", name))
            return;
        if (!configuration.hasKey(category, name)) {
            configuration.moveProperty("general", name, category);
        } else {
            configuration.getCategory("general").remove(name);
        }
    }

    /**
     * Returns a boolean configuration property value with language keys and descriptions.
     *
     * @param category category name string
     * @param name property name string
     * @param defaultValue default boolean value
     * @param description property description string
     * @return boolean property value
     */
    private static boolean bool(String category, String name, boolean defaultValue, String description) {
        String key = category.equals(WORLD_HUD) ? "worldHud." + name : name;
        return configuration.getBoolean(name, category, defaultValue, description, languageKey(key));
    }

    /**
     * Returns an enum option configuration property value.
     *
     * @param category category name string
     * @param name property name string
     * @param defaultValue default enum value
     * @param type enum class type
     * @param description property description string
     * @param <E> enum type
     * @return parsed enum value
     */
    private static <E extends Enum<E>> E option(String category, String name, E defaultValue, Class<E> type,
            String description) {
        String[] values = Arrays.stream(type.getEnumConstants()).map(Enum::name).toArray(String[]::new);
        String key = category.equals(WORLD_HUD) ? "worldHud." + name : name;
        Property property = configuration.get(category, name, defaultValue.name(), description, values)
                .setLanguageKey(languageKey(key));
        try {
            E result = Enum.valueOf(type, property.getString().toUpperCase(Locale.ROOT));
            if (!property.getString().equals(result.name()))
                property.set(result.name());
            return result;
        } catch (IllegalArgumentException ignored) {
            property.set(defaultValue.name());
            return defaultValue;
        }
    }

    /**
     * Generates a language key string for a configuration property name.
     *
     * @param name property name string
     * @return language key string
     */
    private static String languageKey(String name) {
        return "avogadro.configuration." + name;
    }

    /** Generic thread-safe configuration value wrapper container. */
    public static final class Value<T> {

        /** Wrapped value instance. */
        private volatile T value;

        /**
         * Constructs a configuration value wrapper.
         *
         * @param value initial value
         */
        private Value(T value) {
            this.value = value;
        }

        /**
         * Returns the wrapped configuration value.
         *
         * @return wrapped value instance
         */
        public T get() {
            return value;
        }
    }
}

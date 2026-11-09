package io.github.wurtzitane.avogadro.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;

import io.github.wurtzitane.avogadro.Config;
import io.github.wurtzitane.avogadro.api.ElementCatalog;
import io.github.wurtzitane.avogadro.molecule.MoleculeDef;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;

/**
 * Renders structured element cards containing facts, configurations, and reference properties.
 * <p>
 * Shared by the examiner and exports. Reference facts never replace the host's isotope data.
 */
public final class ElementCard {
    public static final int WIDTH = 188, HEIGHT = 270;
    public static final int PAPER = 0xFFFAF8F2;
    private static final int INK = StructureStyle.INK, MUTED = StructureStyle.MUTED, BLUE = 0x24618A;
    private static final int RULE = StructureStyle.RULE;
    private static final Pattern ORBITAL = Pattern.compile("(\\[[^]]+\\])|(\\d+[spdfg])(\\d+)");
    private static final Pattern QUALIFIER = Pattern.compile("\\s*(\\((?:calculated|predicted)\\))\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Map<LayoutKey, Layout> LAYOUTS = new HashMap<>();
    private static long layoutRevision = Long.MIN_VALUE;

    private ElementCard() {}

    /** Returns the selected theme's element-card paper color; PAPER remains the legacy default. */
    public static int paperColor() {
        return UiThemes.color("frame.background", PAPER);
    }

    /** Invalidates measured labels that include resolved theme colors. */
    static void clearThemeLayouts() {
        LAYOUTS.clear();
    }

    /** Returns a reference fact for an element symbol and key. */
    public static String fact(String symbol, String key) {
        return ElementCatalog.fact(symbol, key);
    }

    /**
     * Converts numeric electron counts in an orbital label into superscript unicode characters.
     * <p>
     * Plain-text callers can still use Unicode occupancies; the card draws measured raised digits.
     *
     * @param input raw orbital string
     * @return formatted orbital label with superscript digits
     */
    public static String orbitalLabel(String input) {
        var matcher = Pattern.compile("([spdfg])(\\d+)").matcher(input);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            StringBuilder count = new StringBuilder(matcher.group(1));
            for (char digit : matcher.group(2).toCharArray()) {
                count.append("⁰¹²³⁴⁵⁶⁷⁸⁹".charAt(digit - '0'));
            }
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(count.toString()));
        }
        return matcher.appendTail(result).toString();
    }

    /** Returns the display color associated with an element symbol. */
    public static int color(String symbol) {
        return UiThemes.color("element." + symbol, ElementCatalog.color(symbol));
    }

    /** Theme ink for symbols, with separate defaults for dark and light element tiles. */
    public static int symbolColor(int tileColor) {
        return UiThemes.color("element.symbol." + (isDarkTile(tileColor) ? "light" : "dark"), tileTextColor(tileColor));
    }

    /** Theme ink for atomic numbers, independent of symbols and tile backgrounds. */
    public static int numberColor(int tileColor) {
        return UiThemes.color("element.number." + (isDarkTile(tileColor) ? "light" : "dark"), tileTextColor(tileColor));
    }

    private static boolean isDarkTile(int color) {
        return (color >> 16 & 255) * 299 + (color >> 8 & 255) * 587 + (color & 255) * 114 < 128000;
    }

    /** Calculates text tile color based on background lightness. */
    static int tileTextColor(int background) {
        background = UiThemes.originalColor(background);
        int lightness = 299 * (background >>> 16 & 255) + 587 * (background >>> 8 & 255) + 114 * (background & 255);
        return lightness < 128000 ? UiThemes.color("tile.lightText", 0xFFFFFFFF) : UiThemes.color("tile.darkText", 0xFF3F3A30);
    }

    /**
     * Represents a configuration text run and whether it requires superscript scaling.
     *
     * @param text run string content
     * @param superscript whether the run is superscript
     */
    public record ConfigurationRun(String text, boolean superscript) {
    }

    /** Extracts qualification markers from an electron configuration string. */
    static String configurationQualifier(String input) {
        var qualifier = QUALIFIER.matcher(input);
        return qualifier.find() ? qualifier.group(1) : "";
    }

    /** Resolves neutral electron configuration text considering prediction settings. */
    static String configurationText(String input, String status, boolean predictions) {
        if (input.isBlank() || input.equals("-") || input.equalsIgnoreCase("unknown")
                || input.equalsIgnoreCase("not specified")) {
            return "UNKNOWN";
        }
        boolean estimated = !configurationQualifier(input).isEmpty()
                || List.of("predicted", "calculated", "authored").contains(status.toLowerCase(Locale.ROOT));
        return estimated && !predictions ? "UNKNOWN" : input;
    }

    /** Resolves configuration text for a given element symbol. */
    private static String configurationText(String symbol) {
        return configurationText(fact(symbol, "configuration"), fact(symbol, "configuration_status"),
                Config.PREDICT_NEUTRAL_ELECTRON_CONFIGS.get());
    }

    /**
     * Splits an electron configuration string into wrapped layout lines.
     * <p>
     * An orbital and its occupancy stay together when a configuration wraps.
     *
     * @param input configuration string
     * @param width available display width
     * @param measure function measuring string width
     * @return list of configuration run lines
     */
    public static List<List<ConfigurationRun>> configurationLines(String input, int width,
            ToIntFunction<String> measure) {
        return configurationLines(input, width, measure, 1, .7f);
    }

    /** Splits an configuration string with custom scale factors. */
    private static List<List<ConfigurationRun>> configurationLines(String input, float width,
            ToIntFunction<String> measure, float scale, float superscriptScale) {
        var qualifier = QUALIFIER.matcher(input);
        if (qualifier.find()) {
            input = input.substring(0, qualifier.start());
        }
        var words = new ArrayList<List<ConfigurationRun>>();
        var matcher = ORBITAL.matcher(input);
        int end = 0;
        while (matcher.find()) {
            String prefix = input.substring(end, matcher.start()).trim();
            if (!prefix.isEmpty()) {
                words.add(List.of(new ConfigurationRun(prefix, false)));
            }
            words.add(matcher.group(1) != null
                    ? List.of(new ConfigurationRun(matcher.group(1), false))
                    : List.of(new ConfigurationRun(matcher.group(2), false),
                            new ConfigurationRun(matcher.group(3), true)));
            end = matcher.end();
        }
        String tail = input.substring(end).trim();
        if (!tail.isEmpty()) {
            words.add(List.of(new ConfigurationRun(tail, false)));
        }
        var lines = new ArrayList<List<ConfigurationRun>>();
        var line = new ArrayList<ConfigurationRun>();
        float used = 0;
        for (var word : words) {
            float wordWidth = 0;
            for (var run : word) {
                wordWidth += measure.applyAsInt(run.text()) * (run.superscript() ? superscriptScale : scale);
            }
            float gap = line.isEmpty() ? 0 : measure.applyAsInt(" ") * scale;
            if (!line.isEmpty() && used + gap + wordWidth > width) {
                lines.add(List.copyOf(line));
                line.clear();
                used = 0;
                gap = 0;
            }
            if (gap > 0) {
                line.add(new ConfigurationRun(" ", false));
            }
            line.addAll(word);
            used += gap + wordWidth;
        }
        if (!line.isEmpty()) {
            lines.add(List.copyOf(line));
        }
        return List.copyOf(lines);
    }

    /** Represents a measured text block container. */
    private record TextBlock(float scale, List<String> lines) {
        int lineHeight() {
            return (int) Math.ceil(11 * scale);
        }
        int height() {
            return lines.size() * lineHeight();
        }
    }

    /** Header structure holding name and category text blocks. */
    private record Header(TextBlock name, int categoryTop, TextBlock category) {
    }

    /** Measured configuration block container. */
    private record Configuration(float scale, float superscriptScale, List<List<ConfigurationRun>> lines) {
        int lineHeight() {
            return (int) Math.ceil(Math.max(12 * scale, 9 * superscriptScale + 3 * scale));
        }
        int height() {
            return lines.size() * lineHeight();
        }
    }

    /** Cache key for layout metric measurements. */
    private record LayoutKey(String symbol, float scale, float physicalScale, boolean matches, boolean predictions) {
    }

    /** Complete layout metrics record for drawing element cards. */
    private record Layout(float physicalScale, Header header, int massCaption, TextBlock massHeading, int massTop,
            TextBlock mass, int configurationCaption, TextBlock configurationHeading, int configurationTop,
            Configuration configuration, int propertyTop, TextBlock[] propertyNames, TextBlock[] propertyValues,
            int[] valueOffsets, int[] rowHeights, int divider, float countScale, int countLabelTop,
            TextBlock[] countLabels, int height) {
    }

    /** Container pairing a measured layout with its scaling factor. */
    private record SizedLayout(Layout layout, float scale) {
    }

    /** Returns or computes a cached layout for an element. */
    private static Layout layout(String symbol, float requestedScale, boolean matches) {
        return layout(symbol, requestedScale, matches, 0);
    }

    /** Computes or returns a cached layout with physical scaling. */
    private static Layout layout(String symbol, float requestedScale, boolean matches, float physicalScale) {
        float textScale = Float.isFinite(requestedScale) ? Math.clamp(requestedScale, .5f, 3f) : 1;
        long revision = MoleculeCategoryStyle.reloadRevision();
        if (layoutRevision != revision) {
            LAYOUTS.clear();
            layoutRevision = revision;
        }
        var key = new LayoutKey(symbol, textScale, physicalScale, matches,
                Config.PREDICT_NEUTRAL_ELECTRON_CONFIGS.get());
        Layout cached = LAYOUTS.get(key);
        if (cached != null) {
            return cached;
        }
        // Reuse font measurements between frames, and bound the cache for custom export scales.
        if (LAYOUTS.size() >= 512) {
            LAYOUTS.clear();
        }
        Layout measured = measureLayout(symbol, textScale, matches, physicalScale);
        LAYOUTS.put(key, measured);
        return measured;
    }

    /** Measures and constructs layout metric values. */
    private static Layout measureLayout(String symbol, float textScale, boolean matches, float physicalScale) {
        float bodyScale = pixelScale(textScale, physicalScale);
        float nameScale = Math.min(1.9f, 1.65f * textScale);

        String name = matches ? capital(fact(symbol, "name")) : symbol;
        float singleLineScale = 74f / Math.max(1, font().getStringWidth(name));
        TextBlock nameBlock = singleLineScale >= 1.2f
                ? fittedBlock(name, nameScale, 74, physicalScale)
                : block(name, nameScale, 74, physicalScale);
        int categoryTop = Math.max(40, 17 + nameBlock.height() + 6);
        TextBlock category = block(matches ? categoryName(fact(symbol, "category")) : "Custom atom", 1, 74,
                physicalScale);
        Header header = new Header(nameBlock, categoryTop, category);
        TextBlock massHeading = fittedBlock("REFERENCE MASS", .8f, 168, physicalScale);
        TextBlock configurationHeading = fittedBlock("NEUTRAL ELECTRON CONFIGURATION", .8f, 168, physicalScale);
        int massCaption = Math.max(102, categoryTop + category.height() + 10);
        int massTop = massCaption + Math.max(12, massHeading.height() + 3);
        TextBlock mass = block(matches ? fact(symbol, "mass") : "Not specified", bodyScale, 168, 0);
        int configurationCaption = Math.max(massCaption + 36, massTop + mass.height() + 10);
        int configurationTop = configurationCaption + Math.max(15, configurationHeading.height() + 5);
        Configuration configuration = measuredConfiguration(matches ? configurationText(symbol) : "UNKNOWN", 168,
                textScale, physicalScale);
        int propertyTop = Math.max(massCaption + 68, configurationTop + Math.max(12, configuration.height()) + 5);
        int[] rows = new int[3];
        int[] valueOffsets = new int[3];
        TextBlock[] propertyNames = new TextBlock[3], propertyValues = new TextBlock[3];
        int divider = propertyTop + 2;
        String[] codes = {fact(symbol, "oxide"), fact(symbol, "crystal"), fact(symbol, "reference_state")};
        String[] names = {"Oxide character", "Crystal structure", "Reference state"};
        for (int i = 0; i < rows.length; i++) {
            propertyNames[i] = fittedBlock(names[i], .8f, 136, physicalScale);
            propertyValues[i] = block(propertyValue(i, matches ? codes[i] : "-"), bodyScale, 136, 0);
            valueOffsets[i] = Math.max(11, propertyNames[i].height() + 2);
            rows[i] = Math.max(27, valueOffsets[i] + propertyValues[i].height() + 4);
            divider += rows[i];
        }
        float countScale = pixelScale(Math.min(2.5f, 1.2f * textScale), physicalScale);

        int countLabelTop = 20 + Math.max(0, (int) Math.ceil(font().FONT_HEIGHT * countScale) - 11);
        TextBlock[] countLabels = new TextBlock[3];
        String[] labels = {"Protons", "Neutrons", "Electrons"};
        int countLabelHeight = 9;
        for (int i = 0; i < labels.length; i++) {
            countLabels[i] = fittedBlock(labels[i], .8f, 54, physicalScale);
            countLabelHeight = Math.max(countLabelHeight, countLabels[i].height());
        }
        return new Layout(physicalScale, header, massCaption, massHeading, massTop, mass, configurationCaption,
                configurationHeading, configurationTop, configuration, propertyTop, propertyNames, propertyValues,
                valueOffsets, rows, divider, countScale, countLabelTop, countLabels,
                divider + countLabelTop + countLabelHeight);
    }

    /** Snaps pixel scale values to physical screen pixels. */
    static float pixelScale(float requested, float physicalScale) {
        return physicalScale > 0 && Float.isFinite(physicalScale)
                ? Math.max(1, Math.round(requested * physicalScale)) / physicalScale
                : requested;
    }

    /** Calculates fitted font scaling to fit specified widths. */
    private static float fittedScale(String value, float requested, int width, float physicalScale) {
        float limit = width / (float) Math.max(1, font().getStringWidth(value));
        if (!(physicalScale > 0)) {
            return Math.min(requested, limit);
        }
        int pitch = Math.max(1, Math.round(requested * physicalScale));
        int fittingPitch = Math.max(1, (int) Math.floor(limit * physicalScale + 1e-4f));
        return Math.min(pitch, fittingPitch) / physicalScale;
    }

    /** Creates a text block wrapping text within a given width. */
    private static TextBlock block(String value, float requested, int width, float physicalScale) {
        float scale = pixelScale(requested, physicalScale);
        return new TextBlock(scale, List.copyOf(
                font().listFormattedStringToWidth(value, Math.max(1, (int) Math.floor(width / scale + 1e-4f)))));
    }

    /** Creates a fitted text block wrapping text within a given width. */
    private static TextBlock fittedBlock(String value, float requested, int width, float physicalScale) {
        return block(value, fittedScale(value, requested, width, physicalScale), width, 0);
    }

    /** Computes a sized layout fitting specified panel dimensions. */
    private static SizedLayout sizedLayout(String symbol, float textScale, boolean matches, int width, int height,
            float parentScale) {
        Layout natural = layout(symbol, textScale, matches);
        float scale = Math.min(width / (float) WIDTH, height / (float) natural.height());
        if (!(parentScale > 0) || !Float.isFinite(parentScale)) {
            return new SizedLayout(natural, scale);
        }
        for (int attempt = 0; attempt < 8; attempt++) {
            Layout measured = layout(symbol, textScale, matches, parentScale * scale);
            float fittingScale = Math.min(width / (float) WIDTH, height / (float) measured.height());
            if (fittingScale >= scale - 1e-5f) {
                return new SizedLayout(measured, scale);
            }
            scale = fittingScale;
        }
        return new SizedLayout(natural, Math.min(width / (float) WIDTH, height / (float) natural.height()));
    }

    /** Returns the total content height for an element card. */
    public static int contentHeight(String symbol, float textScale) {
        return layout(symbol, textScale, true).height();
    }

    /** Resolves configuration tooltips for hovered regions on element cards. */
    static String configurationTooltip(MoleculeDef definition, int index, int width, int height, float mouseX,
            float mouseY) {
        if (width <= 0 || height <= 0 || !Float.isFinite(mouseX) || !Float.isFinite(mouseY)) {
            return "";
        }
        if (index < 0 || index >= definition.structure().atoms().size()) {
            return "";
        }
        String symbol = definition.structure().atoms().get(index).element().symbol();
        var configuration = definition.inspection().atom(definition.structure(), index);
        if (configuration != null && !fact(symbol, "number").equals(Integer.toString(configuration.atomicNumber()))) {
            return "";
        }
        if (configurationText(symbol).equals("UNKNOWN")) {
            return "";
        }
        String qualifier = configurationQualifier(fact(symbol, "configuration"));
        if (qualifier.isEmpty()) {
            return "";
        }
        SizedLayout sized = sizedLayout(symbol, 1, true, width, height, PixelFont.physicalScale());
        Layout layout = sized.layout();
        float x = (mouseX - (width - WIDTH * sized.scale()) / 2) / sized.scale();
        float y = mouseY / sized.scale();
        return x >= 10 && x < 178 && y >= layout.configurationCaption() && y < layout.propertyTop() - 6
                ? qualifier
                : "";
    }

    /** Checks whether the configuration section is currently hovered. */
    static boolean configurationHovered(int width, int height, int cardHeight, int captionTop, int propertyTop,
            float mouseX, float mouseY) {
        if (width <= 0 || height <= 0 || cardHeight <= 0 || !Float.isFinite(mouseX) || !Float.isFinite(mouseY)) {
            return false;
        }
        float scale = Math.min(width / (float) WIDTH, height / (float) cardHeight);
        float x = (mouseX - (width - WIDTH * scale) / 2) / scale;
        float y = mouseY / scale;
        return x >= 10 && x < 178 && y >= captionTop && y < propertyTop - 6;
    }

    /** Draws an element card for a molecule definition atom index. */
    public static void draw(MoleculeDef definition, int index, int width, int height) {
        draw(definition, index, width, height, 1);
    }

    /** Draws an element card with a custom text scale factor. */
    public static void draw(MoleculeDef definition, int index, int width, int height, float textScale) {
        if (width <= 0 || height <= 0) {
            return;
        }
        var atom = definition.structure().atoms().get(index);
        String symbol = atom.element().symbol();
        var config = definition.inspection().atom(definition.structure(), index);
        boolean matches = config == null || fact(symbol, "number").equals(Integer.toString(config.atomicNumber()));
        // A caller may have changed GL directly while drawing a model or framebuffer.
        org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0);
        GlStateManager.bindTexture(0);
        Gui.drawRect(0, 0, width, height, StructureStyle.PAPER());
        SizedLayout sized = sizedLayout(symbol, textScale, matches, width, height, PixelFont.physicalScale());
        Layout layout = sized.layout();
        float scale = sized.scale();
        GlStateManager.pushMatrix();
        GlStateManager.translate((width - WIDTH * scale) / 2, 0, 0);
        GlStateManager.scale(scale, scale, 1);
        try {
            // The category color belongs only to the small periodic-table tile.
            int tileColor = matches ? color(symbol) : UiThemes.color("tile.unknown", 0xFFE3E3DE);
            Gui.drawRect(10, 10, 90, 90, tileColor);
            String number = config == null ? fact(symbol, "number") : Integer.toString(config.atomicNumber());
            try (var ignored = UiThemes.element("element/" + symbol + "/number")) {
                text(number, 17, 17, fittedScale(number, 1.15f, 66, layout.physicalScale()), numberColor(tileColor));
            }
            float symbolScale = fittedScale(symbol, 5.2f, 66, layout.physicalScale());
            try (var ignored = UiThemes.element("element/" + symbol + "/symbol")) {
                text(symbol, 50 - font().getStringWidth(symbol) * symbolScale / 2,
                        58 - font().FONT_HEIGHT * symbolScale / 2, symbolScale, symbolColor(tileColor));
            }
            Header header = layout.header();
            textBlock("name", header.name(), 104, 17, UiThemes.color("text.title", StructureStyle.INK));
            textBlock("category", header.category(), 104, header.categoryTop(), StructureStyle.MUTED());

            try (var ignored = UiThemes.element("reference-mass")) {
                caption(layout.massHeading(), layout.massCaption());
                textBlock("value", layout.mass(), 10, layout.massTop(), StructureStyle.INK());
            }
            try (var ignored = UiThemes.element("electron-configuration")) {
                caption(layout.configurationHeading(), layout.configurationCaption());
                configuration(layout.configuration(), 10, layout.configurationTop());
            }

            int y = layout.propertyTop();
            property(0, matches ? fact(symbol, "oxide") : "-", y, layout);
            y += layout.rowHeights()[0];
            property(1, matches ? fact(symbol, "crystal") : "-", y, layout);
            y += layout.rowHeights()[1];
            property(2, matches ? fact(symbol, "reference_state") : "-", y, layout);

            Gui.drawRect(10, layout.divider(), 178, layout.divider() + 1, StructureStyle.RULE());
            if (config != null) {
                int[] counts = {config.atomicNumber(), config.neutrons(), config.electrons()};
                for (int i = 0; i < 3; i++) {
                    try (var ignored = UiThemes.element("particle-count-" + i)) {
                    float column = 10 + i * 56;
                    String count = Integer.toString(counts[i]);
                    text("value", count, column, layout.divider() + 6,
                            fittedScale(count, layout.countScale(), 54, layout.physicalScale()), StructureStyle.INK());
                    textBlock("label", layout.countLabels()[i], column, layout.divider() + layout.countLabelTop(), StructureStyle.MUTED());
                    }
                }
            }
        } finally {
            GlStateManager.popMatrix();
            GlStateManager.color(1, 1, 1, 1);
        }
    }

    /** Draws a section caption divider and heading. */
    private static void caption(TextBlock heading, int y) {
        Gui.drawRect(10, y - 7, 178, y - 6, StructureStyle.RULE());
        textBlock("heading", heading, 10, y, StructureStyle.MUTED());
    }

    /** Calculates natural font scaling for electron configurations. */
    private static float configurationScale(String input, int width, float requested) {
        float natural = 0;
        for (var line : configurationLines(input, Integer.MAX_VALUE, font()::getStringWidth)) {
            for (var run : line) {
                natural += font().getStringWidth(run.text()) * (run.superscript() ? .7f : 1);
            }
        }
        return Math.min(requested, (width - 1) / Math.max(1, natural));
    }

    /** Measures and constructs configuration display layouts. */
    private static Configuration measuredConfiguration(String input, int width, float textScale, float physicalScale) {
        float scale = pixelScale(configurationScale(input, width, textScale), physicalScale);
        float superscriptScale = pixelScale(.7f * scale, physicalScale);

        return new Configuration(scale, superscriptScale,
                configurationLines(input, width, font()::getStringWidth, scale, superscriptScale));
    }

    /** Draws measured configuration text lines with correct superscript offsets. */
    private static void configuration(Configuration configuration, int x, int y) {
        for (int row = 0; row < configuration.lines().size(); row++) {
            float cursor = x;
            for (var run : configuration.lines().get(row)) {
                float scale = run.superscript() ? configuration.superscriptScale() : configuration.scale();
                text("value", run.text(), cursor,
                        y + row * configuration.lineHeight() - (run.superscript() ? 3 * configuration.scale() : 0),
                        scale, StructureStyle.INK());
                cursor += font().getStringWidth(run.text()) * scale;
            }
        }
    }

    /** Draws an individual property row with its corresponding icon. */
    private static void property(int kind, String code, int y, Layout layout) {
        try (var ignored = UiThemes.element("reference-property-" + kind)) {
        switch (kind) {
            case 0 -> ElementCardIcons.drawOxide(code, 12, y, 20, UiThemes.color("icon.primary", BLUE));
            case 1 -> ElementCardIcons.drawCrystal(code, 12, y, 20, UiThemes.color("icon.primary", BLUE));
            case 2 -> ElementCardIcons.drawState(code, 12, y, 20, UiThemes.color("icon.primary", BLUE));
            default -> throw new IllegalArgumentException("Unknown reference property");
        }
        textBlock("label", layout.propertyNames()[kind], 42, y, StructureStyle.MUTED());
        textBlock("value", layout.propertyValues()[kind], 42, y + layout.valueOffsets()[kind], StructureStyle.INK());
        }
    }

    /** Translates property codes into human-readable text values. */
    static String propertyValue(int kind, String code) {
        return switch (kind) {
            case 0 -> switch (code) {
                case "sb" -> "Strongly basic";
                case "wb" -> "Weakly basic";
                case "eq" -> "Amphoteric";
                case "wa" -> "Weakly acidic";
                case "sa" -> "Strongly acidic";
                case "none" -> "Not applicable";
                default -> "Not specified";
            };
            case 1 -> switch (code) {
                case "hex" -> "Hexagonal";
                case "bcc" -> "Body-centred cubic";
                case "hcp" -> "Hexagonal close-packed";
                case "diamond" -> "Diamond cubic";
                case "neutron-cluster" -> "Neutron-cluster packing";
                case "fcc" -> "Face-centred cubic";
                case "ortho" -> "Orthorhombic";
                case "rhomb" -> "Rhombohedral";
                case "tetr" -> "Tetragonal";
                case "mono" -> "Monoclinic";
                case "cubic" -> "Cubic";
                default -> "Not specified";
            };
            case 2 -> code.equals("synthetic") ? "Synthetic" : code.equals("-") ? "Not specified" : capital(code);
            default -> "";
        };
    }

    /** Capitalizes the first letter of a string. */
    private static String capital(String text) {
        return text.isEmpty() ? text : text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
    }

    /** Maps element category keys to display names. */
    private static String categoryName(String value) {
        return switch (value) {
            case "alkali" -> "Alkali metal";
            case "alkaline" -> "Alkaline-earth metal";
            case "transition" -> "Transition metal";
            case "rare-earth" -> "Rare-earth element";
            case "actinoid" -> "Actinoid element";
            case "noble" -> "Noble gas";
            default -> capital(value);
        };
    }

    /** Returns the pixel font renderer instance. */
    private static FontRenderer font() {
        return PixelFont.renderer();
    }

    /** Draws a measured text block. */
    private static void textBlock(TextBlock block, float x, float y, int color) {
        for (int i = 0; i < block.lines().size(); i++) {
            text(block.lines().get(i), x, y + i * block.lineHeight(), block.scale(), color);
        }
    }

    /** Keeps a wrapped label's color address independent of its measured line count. */
    private static void textBlock(String name, TextBlock block, float x, float y, int color) {
        String role = UiThemes.textRole(color);
        try (var ignored = UiThemes.element(name)) {
            textBlock(block, x, y, UiThemes.color(role, UiThemes.originalColor(color)));
        }
    }

    private static void text(String name, String value, float x, float y, float scale, int color) {
        String role = UiThemes.textRole(color);
        try (var ignored = UiThemes.element(name)) {
            text(value, x, y, scale, UiThemes.color(role, UiThemes.originalColor(color)));
        }
    }

    /** Draws scaled text strings with proper OpenGL state setups. */
    private static void text(String value, float x, float y, float scale, int color) {
        // Offscreen capture resets raw texture state. Keep the native font's cache in sync.
        GlStateManager.setActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
        org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
        org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0);
        GlStateManager.bindTexture(0);
        GlStateManager.enableTexture2D();
        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_TEXTURE_2D);
        GlStateManager.enableAlpha();
        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_ALPHA_TEST);
        org.lwjgl.opengl.GL11.glAlphaFunc(org.lwjgl.opengl.GL11.GL_GREATER, .1f);
        GlStateManager.enableBlend();
        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_BLEND);
        GlStateManager.tryBlendFuncSeparate(org.lwjgl.opengl.GL11.GL_SRC_ALPHA,
                org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA, org.lwjgl.opengl.GL11.GL_ONE,
                org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0);
        GlStateManager.scale(scale, scale, 1);
        try {
            font().drawString(value, 0, 0, color);
        } finally {
            GlStateManager.popMatrix();
            GlStateManager.color(1, 1, 1, 1);
        }
    }
}

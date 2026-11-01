package io.github.wurtzitane.avogadro.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.wurtzitane.avogadro.Avogadro;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import net.minecraft.util.ResourceLocation;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/**
 * Manages molecule category color styles, font themes, and resource reloading hooks.
 * <p>Recipe textures, fonts and text colors follow the selected resource packs.
 */
public final class MoleculeCategoryStyle implements IResourceManagerReloadListener {

    /** Singleton instance of the molecule category style reload listener. */
    public static final MoleculeCategoryStyle INSTANCE = new MoleculeCategoryStyle();

    /** Resource location for molecule GUI textures. */
    public static final ResourceLocation TEXTURE = Avogadro.rl("textures/gui/molecules.png");

    /** Resource location for molecule category color JSON configuration. */
    private static final ResourceLocation COLORS = Avogadro.rl("gui/molecules.json");

    /** Default fallback palette instance. */
    private static final Palette DEFAULT = new Palette(0x3F3A30, 0x6C6557);

    /** Current volatile palette instance. */
    private static volatile Palette palette = DEFAULT;

    /** Reload revision counter tracking style updates. */
    private static volatile long reloadRevision;

    /** Private constructor to prevent instantiation of utility class. */
    private MoleculeCategoryStyle() {}

    /** Returns the current palette configuration. */
    public static Palette palette() {
        return palette;
    }

    /** Returns the current reload revision timestamp or identifier. */
    public static long reloadRevision() {
        return reloadRevision;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onResourceManagerReload(IResourceManager manager) {
        reloadRevision++;
        MoleculeRotation.cancelActiveDrag();
        MoleculeCamera.resetAll();
        palette = DEFAULT;
        try (IResource resource = manager.getResource(COLORS);
             Reader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
            palette = parse(JsonParser.parseReader(reader).getAsJsonObject());
        } catch (Exception e) {
            Avogadro.LOGGER.warn("Cannot load molecule category colors from {}; using defaults", COLORS, e);
        }
    }

    /** Parses palette colors from a JSON object. */
    static Palette parse(JsonObject json) {
        for (String key : json.keySet()) {
            if (!key.equals("primaryTextColor") && !key.equals("secondaryTextColor")) {
                throw new IllegalArgumentException("Unknown molecule category color: " + key);
            }
        }
        return new Palette(color(json, "primaryTextColor", DEFAULT.primary()),
                color(json, "secondaryTextColor", DEFAULT.secondary()));
    }

    /** Parses an individual hex color entry with a fallback. */
    private static int color(JsonObject json, String name, int fallback) {
        if (!json.has(name)) {
            return fallback;
        }
        String value = json.get(name).getAsString();
        if (!value.matches("#[0-9a-fA-F]{6}")) {
            throw new IllegalArgumentException(name + " must be a #RRGGBB color");
        }
        return Integer.parseInt(value.substring(1), 16);
    }

    /**
     * Represents a primary and secondary color palette.
     *
     * @param primary   primary text color
     * @param secondary secondary text color
     */
    public record Palette(int primary, int secondary) {}
}
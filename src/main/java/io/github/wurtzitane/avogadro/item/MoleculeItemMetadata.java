package io.github.wurtzitane.avogadro.item;

import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import io.github.wurtzitane.avogadro.api.MoleculeWeight;
import io.github.wurtzitane.avogadro.molecule.MoleculeDef;
import io.github.wurtzitane.avogadro.molecule.MoleculeLoader;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.WeakHashMap;

/** Cached immutable metadata; independent of the item registry and mutable ItemStacks. */
final class MoleculeItemMetadata {

    /** Maximum capacity limit for the validated resource identifier cache. */
    private static final int ID_CACHE_LIMIT = 1024;

    /** LRU cache map storing parsed resource location identifiers for text strings. */
    private static final Map<String, Optional<ResourceLocation>> IDS = new LinkedHashMap<>(64, .75f, true) {

        /**
         * Determines whether the eldest entry should be removed when exceeding the cache limit.
         *
         * @param eldest eldest map entry
         * @return {@code true} if size exceeds limit, {@code false} otherwise
         */
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Optional<ResourceLocation>> eldest) {
            return size() > ID_CACHE_LIMIT;
        }
    };

    /** Synchronized weak map storing fallback names for molecule definitions. */
    private static final Map<MoleculeDef, String> FALLBACK_NAMES = Collections.synchronizedMap(new WeakHashMap<>());

    /** Synchronized weak map storing precalculated molar mass or entity weight text. */
    private static final Map<MoleculeDef, Optional<String>> WEIGHT_TEXT = Collections
            .synchronizedMap(new WeakHashMap<>());

    /** Private constructor to prevent instantiation of utility class. */
    private MoleculeItemMetadata() {}

    /**
     * Retrieves a validated resource location identifier for the given text string.
     *
     * @param text identifier text string
     * @return validated resource location, or {@code null}
     */
    // Cache the value read now, never an ItemStack or its mutable NBT compound. Oversized NBT
    // strings use the original validation path without occupying persistent cache storage.
    static ResourceLocation validatedId(String text) {
        if (text.length() > 1024) return parseId(text).orElse(null);
        synchronized (IDS) {
            return IDS.computeIfAbsent(text, MoleculeItemMetadata::parseId).orElse(null);
        }
    }

    /**
     * Parses a resource location identifier from text safely.
     *
     * @param text identifier text string
     * @return optional resource location
     */
    private static Optional<ResourceLocation> parseId(String text) {
        try {
            return Optional.of(MoleculeLoader.identifier(text));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    /**
     * Retrieves or generates a formatted fallback name for a molecule definition.
     *
     * @param definition molecule definition
     * @return formatted fallback name string
     */
    static String fallbackName(MoleculeDef definition) {
        if (definition.name() != null) return definition.name();
        return FALLBACK_NAMES.computeIfAbsent(definition, value -> {
            String path = value.id().getPath();
            String[] words = path.substring(path.lastIndexOf('/') + 1).replace('_', ' ').replace('-', ' ').split(" +");
            StringJoiner result = new StringJoiner(" ");
            for (String word : words)
                if (!word.isEmpty()) result.add(Character.toUpperCase(word.charAt(0)) + word.substring(1));
            return result.toString();
        });
    }

    /**
     * Computes and caches the weight/molar mass text representation for a molecule definition.
     *
     * @param definition molecule definition
     * @return optional weight text string
     */
    static Optional<String> weightText(MoleculeDef definition) {
        return WEIGHT_TEXT.computeIfAbsent(definition, value -> {
            try {
                if (value.inspection().metadata().containsKey(io.github.wurtzitane.avogadro.api.PolymerNetworkDefinition.METADATA_KEY))
                    return Optional.of(MoleculeWeight.calculate(value.structure()).molarMassText());
                if (io.github.wurtzitane.avogadro.api.MoleculeClassification.of(value) ==
                        io.github.wurtzitane.avogadro.api.MoleculeClassification.POLYMER)
                    return Optional.of(MoleculeWeight.calculate(
                            io.github.wurtzitane.avogadro.api.PolymerDefinition.of(value).repeatUnitFormula()).molarMassText());
                return Optional.of((io.github.wurtzitane.avogadro.api.MoleculeClassification.of(value) ==
                        io.github.wurtzitane.avogadro.api.MoleculeClassification.CRYSTAL ?
                        MoleculeWeight.calculate(value).molarMassText() :
                        MoleculeWeight.calculate(value).entityMassText()));
            } catch (IllegalArgumentException unsupported) {
                return Optional.empty();
            }
        });
    }

    /**
     * Retrieves the localized display name for a molecule definition on the client side.
     *
     * @param definition molecule definition
     * @return localized display name string
     */
    @SideOnly(Side.CLIENT)
    static String displayName(MoleculeDef definition) {
        return TranslatedNames.get(definition);
    }

    /** Helper class managing localized translated name caches for client display. */
    @SideOnly(Side.CLIENT)
    private static final class TranslatedNames {

        /** Cached translated names mapped by molecule definition. */
        private static final Map<MoleculeDef, String> NAMES = new WeakHashMap<>();

        /** Revision tracker for resource packs and language reloads. */
        private static long revision = Long.MIN_VALUE;

        /**
         * Retrieves the localized name for a molecule definition, clearing cache on revision changes.
         *
         * @param definition molecule definition
         * @return translated display name string
         */
        static synchronized String get(MoleculeDef definition) {
            long current = io.github.wurtzitane.avogadro.client.MoleculeCategoryStyle.reloadRevision();
            if (revision != current) {
                NAMES.clear();
                revision = current;
            }
            return NAMES.computeIfAbsent(definition, value -> {
                if (value.name() != null) return net.minecraft.client.resources.I18n.hasKey(value.name()) ?
                        net.minecraft.client.resources.I18n.format(value.name()) : value.name();
                String key = "molecule." + value.id().getNamespace() + "." + value.id().getPath().replace('/', '.');
                return net.minecraft.client.resources.I18n.hasKey(key) ?
                        net.minecraft.client.resources.I18n.format(key) : fallbackName(value);
            });
        }
    }
}
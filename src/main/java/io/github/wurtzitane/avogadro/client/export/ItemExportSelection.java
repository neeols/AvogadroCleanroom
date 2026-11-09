package io.github.wurtzitane.avogadro.client.export;

import java.util.Objects;

import io.github.wurtzitane.avogadro.client.PixelFont;
import io.github.wurtzitane.avogadro.molecule.MoleculeDef;
import io.github.wurtzitane.avogadro.molecule.MoleculeStore;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextFormatting;

/**
 * Represents an accepted item stack and its presentation metadata, independent of subsequent inventory changes.
 *
 * @param stack the copied item stack being exported
 * @param definition the matching {@link MoleculeDef} for the item
 * @param title the formatted display title of the item
 * @param font the font renderer used for drawing item text
 */
public record ItemExportSelection(ItemStack stack, MoleculeDef definition, String title, FontRenderer font) {

    /** Compact constructor validating and defensively copying item stack parameters. */
    public ItemExportSelection {
        stack = Objects.requireNonNull(stack, "stack").copy();
        if (stack.isEmpty()) {
            throw new IllegalArgumentException("Choose a nonempty item stack.");
        }
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(font, "font");
    }

    /**
     * Returns a copy of the selected item stack.
     *
     * @return a defensive copy of the item stack
     */
    @Override
    public ItemStack stack() {
        return stack.copy();
    }

    /**
     * Selects and resolves an item export selection from a target specifier, metadata, and held stack.
     *
     * @param target the target specifier (e.g., "held" or a registry ID)
     * @param metadata optional item metadata value
     * @param held the player's currently held mainhand stack
     * @return a new validated {@link ItemExportSelection}
     * @throws IllegalArgumentException if the stack cannot be resolved or lacks a matching molecule definition
     */
    public static ItemExportSelection select(String target, Integer metadata, ItemStack held) {
        ItemStack stack = resolveStack(target, metadata, held);
        MoleculeDef definition = MoleculeStore.view().find(stack);
        if (definition == null) {
            throw new IllegalArgumentException(
                    "No loaded molecule matches this item. Use a bound item or filled container.");
        }
        FontRenderer font = stack.getItem().getFontRenderer(stack);
        font = PixelFont.wrap(font);
        return new ItemExportSelection(stack, definition, title(stack), font);
    }

    /**
     * Resolves a target item string and metadata into an active {@link ItemStack}.
     *
     * @param target target specifier string
     * @param metadata optional metadata integer
     * @param held held mainhand stack
     * @return the resolved {@link ItemStack}
     * @throws IllegalArgumentException if arguments or registry lookups are invalid
     */
    static ItemStack resolveStack(String target, Integer metadata, ItemStack held) {
        if (metadata != null && (metadata < 0 || metadata > 32767)) {
            throw new IllegalArgumentException("--meta must be a whole number from 0 to 32767.");
        }
        if (target.equalsIgnoreCase("held")) {
            if (metadata != null) {
                throw new IllegalArgumentException("--meta cannot change a held item. Supply a registry ID instead.");
            }
            if (held == null || held.isEmpty()) {
                throw new IllegalArgumentException("Hold a nonempty item in your main hand.");
            }
            return held.copy();
        }
        ResourceLocation id;
        try {
            id = new ResourceLocation(target);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Invalid item registry ID: " + target + ".");
        }
        Item item = Item.REGISTRY.getObject(id);
        if (item == null || item == Items.AIR) {
            throw new IllegalArgumentException("Unknown or empty item: " + target + ".");
        }
        return new ItemStack(item, 1, metadata == null ? 0 : metadata);
    }

    /**
     * Generates the styled title string for an item stack including rarity and italics formatting.
     *
     * @param stack the item stack
     * @return formatted title string
     */
    static String title(ItemStack stack) {
        var rarity = stack.getItem().getForgeRarity(stack).getColor();
        // Common items use the card's contrasting ink; rarity colors retain their meaning.
        return (rarity == TextFormatting.WHITE ? TextFormatting.RESET : rarity)
                + (stack.hasDisplayName() ? TextFormatting.ITALIC.toString() : "") + stack.getDisplayName()
                + TextFormatting.RESET;
    }
}

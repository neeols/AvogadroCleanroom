package io.github.wurtzitane.avogadro.molecule;

import net.minecraft.nbt.*;
import net.minecraft.util.ResourceLocation;

/**
 * A registry ID, optional exact metadata, and optional partial compound-NBT predicate. - Selectors retain the existing
 * ID/@metadata syntax and append # followed by SNBT.
 */

public final class ItemAssociation {

    /** Registry identifier for the item. */
    private final ResourceLocation item;

    /** Optional exact metadata integer value. */
    private final Integer metadata;

    /** Optional required NBT compound tag predicate. */
    private final NBTTagCompound requiredNbt;

    /**
     * Constructs an item association with a specified item and optional metadata.
     *
     * @param item item resource location
     * @param metadata metadata integer, or {@code null}
     */
    public ItemAssociation(ResourceLocation item, Integer metadata) {
        this(item, metadata, null);
    }

    /**
     * Constructs an item association with an item, optional metadata, and required NBT.
     *
     * @param item item resource location
     * @param metadata metadata integer, or {@code null}
     * @param requiredNbt required NBT compound tag, or {@code null}
     */
    public ItemAssociation(ResourceLocation item, Integer metadata, NBTTagCompound requiredNbt) {
        this.item = java.util.Objects.requireNonNull(item, "item");
        if (metadata != null && metadata < 0)
            throw new IllegalArgumentException("Item metadata must be nonnegative");
        this.metadata = metadata;
        this.requiredNbt = requiredNbt == null ? null : requiredNbt.copy();
    }

    /**
     * Returns the item resource location.
     *
     * @return item resource location
     */
    public ResourceLocation item() {
        return item;
    }

    /**
     * Returns the optional metadata integer.
     *
     * @return metadata integer, or {@code null}
     */
    public Integer metadata() {
        return metadata;
    }

    /**
     * Checks whether this item association specifies required NBT tags.
     *
     * @return {@code true} if required NBT is present, {@code false} otherwise
     */
    public boolean hasNbt() {
        return requiredNbt != null;
    }

    /**
     * Returns a copy of the required NBT compound tag predicate.
     *
     * @return cloned NBT compound tag, or {@code null}
     */
    public NBTTagCompound requiredNbt() {
        return requiredNbt == null ? null : requiredNbt.copy();
    }

    /**
     * Evaluates whether actual metadata and NBT match the item association criteria.
     *
     * @param metadata actual item metadata
     * @param actual actual NBT compound tag
     * @return {@code true} if matching, {@code false} otherwise
     */
    public boolean matches(int metadata, NBTTagCompound actual) {
        return (this.metadata == null || this.metadata == metadata)
                && (requiredNbt == null || NBTUtil.areNBTEquals(requiredNbt, actual, true));
    }

    /**
     * Generates a string selector representation combining the item ID, metadata, and NBT.
     *
     * @return selector string
     */
    public String selector() {
        return item + (metadata == null ? "" : "@" + metadata) + (requiredNbt == null ? "" : "#" + requiredNbt);
    }

    /**
     * Parses an item association from a selector string.
     *
     * @param selector selector string
     * @return parsed {@link ItemAssociation}
     */
    public static ItemAssociation parse(String selector) {
        if (selector == null || selector.length() > 65536)
            throw new IllegalArgumentException("Invalid or oversized item association");
        int nbtStart = selector.indexOf('#');
        String base = nbtStart < 0 ? selector : selector.substring(0, nbtStart);
        int metaStart = base.lastIndexOf('@');
        ResourceLocation item = MoleculeLoader.identifier(metaStart < 0 ? base : base.substring(0, metaStart));
        Integer metadata = null;
        if (metaStart >= 0) {
            try {
                metadata = Integer.parseInt(base.substring(metaStart + 1));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid item metadata: " + base, exception);
            }
        }
        NBTTagCompound nbt = null;
        if (nbtStart >= 0) {
            String source = selector.substring(nbtStart + 1);
            checkNesting(source);
            try {
                nbt = JsonToNBT.getTagFromJson(source);
            } catch (NBTException exception) {
                throw new IllegalArgumentException("Invalid item association NBT", exception);
            }
        }
        return new ItemAssociation(item, metadata, nbt);
    }

    /**
     * Validates nesting depth and quote boundaries within NBT source strings.
     *
     * @param source NBT source string
     */
    private static void checkNesting(String source) {
        int depth = 0;
        boolean quoted = false, escaped = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (quoted) {
                if (escaped)
                    escaped = false;
                else if (c == '\\')
                    escaped = true;
                else if (c == '\"')
                    quoted = false;
            } else if (c == '\"') {
                quoted = true;
            } else if (c == '{' || c == '[') {
                if (++depth > 64)
                    throw new IllegalArgumentException("Item association NBT nesting exceeds limit");
            } else if (c == '}' || c == ']') {
                if (--depth < 0)
                    throw new IllegalArgumentException("Invalid item association NBT nesting");
            }
        }
        if (depth != 0 || quoted)
            throw new IllegalArgumentException("Invalid item association NBT syntax");
    }
}

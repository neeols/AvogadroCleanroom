package io.github.wurtzitane.avogadro.molecule;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTUtil;
import net.minecraft.util.ResourceLocation;

import java.util.Objects;

/** A normalized fluid registry ID and optional partial compound-NBT predicate. */
public final class FluidAssociation {

    /** Normalized resource location identifying the fluid. */
    private final ResourceLocation fluid;

    /** Optional required NBT compound tag predicate for fluid matching. */
    private final NBTTagCompound requiredNbt;

    /**
     * Constructs a fluid association without NBT requirements.
     *
     * @param fluid fluid resource location
     */
    public FluidAssociation(ResourceLocation fluid) { this(fluid, null); }

    /**
     * Constructs a fluid association with a specified fluid resource location and optional required NBT.
     *
     * @param fluid       fluid resource location
     * @param requiredNbt required NBT compound tag predicate, or {@code null}
     */
    public FluidAssociation(ResourceLocation fluid, NBTTagCompound requiredNbt) {
        this.fluid = Objects.requireNonNull(fluid, "fluid");
        this.requiredNbt = requiredNbt == null ? null : requiredNbt.copy();
    }

    /**
     * Retrieves the fluid resource location.
     *
     * @return fluid resource location
     */
    public ResourceLocation fluid() { return fluid; }

    /**
     * Checks whether this fluid association specifies required NBT tags.
     *
     * @return {@code true} if required NBT is present, {@code false} otherwise
     */
    public boolean hasNbt() { return requiredNbt != null; }

    /**
     * Retrieves a copy of the required NBT compound tag predicate.
     *
     * @return cloned NBT compound tag, or {@code null}
     */
    public NBTTagCompound requiredNbt() { return requiredNbt == null ? null : requiredNbt.copy(); }

    /**
     * Evaluates whether actual NBT compound tags match the required predicate.
     *
     * @param actual actual NBT compound tag to check
     * @return {@code true} if matching or no NBT required, {@code false} otherwise
     */
    public boolean matches(NBTTagCompound actual) {
        return requiredNbt == null || NBTUtil.areNBTEquals(requiredNbt, actual, true);
    }

    /**
     * Generates a string selector representation combining the fluid location and optional NBT.
     *
     * @return selector string
     */
    public String selector() { return fluid + (requiredNbt == null ? "" : "#" + requiredNbt); }

    /**
     * Parses a fluid association from a selector string.
     *
     * @param selector selector string
     * @return parsed {@link FluidAssociation} instance
     */
    public static FluidAssociation parse(String selector) {
        // Use the same bounded SNBT parser as item bindings so predicate semantics stay identical.
        ItemAssociation parsed;
        try {
            parsed = ItemAssociation.parse(selector);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid fluid association: " + exception.getMessage(), exception);
        }
        if (parsed.metadata() != null) throw new IllegalArgumentException("Fluid associations do not have item metadata");
        return new FluidAssociation(parsed.item(), parsed.requiredNbt());
    }
}
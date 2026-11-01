package io.github.wurtzitane.avogadro.compat.gt;

import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Optional adapter for GregTechCEu 1.12.2 2.8.10-beta (release tag v2.8.10), source commit
 * 9fe140febe8747bbe2f06dfd570421331ec06f4b. No GregTech classes are linked
 * when the standalone library is loaded, including on a dedicated server.
 */
public final class GTCompat {

    /** Mod identifier for GregTech. */
    public static final String MODID = "gregtech";

    /** Supported GregTech version string. */
    public static final String SUPPORTED_VERSION = "2.8.10-beta";

    /** Logger instance for GregTech compatibility diagnostics. */
    private static final Logger LOG = LogManager.getLogger("Avogadro/GTCompat");

    /** Active reflection adapter instance, or {@code null} if unavailable. */
    private static volatile Adapter adapter;

    /** Flag indicating whether adapter discovery has been initialized. */
    private static volatile boolean initialized;

    /** Flag indicating whether unavailability warning has been logged. */
    private static boolean warned;

    /** Reason description for why the adapter is unavailable. */
    private static String unavailableReason = "GregTechCEu 1.12.2 " + SUPPORTED_VERSION + " is not installed";

    /** Private constructor to prevent instantiation of utility class. */
    private GTCompat() {}

    /**
     * Checks whether the GregTech compatibility adapter is successfully loaded and active.
     *
     * @return {@code true} if loaded, {@code false} otherwise
     */
    public static boolean isLoaded() {
        return activeAdapter() != null;
    }

    /** Call once definitions actually use material associations, not for every item hover. */
    public static synchronized void warnIfUnavailable() {
        if (!isLoaded() && !warned) {
            warned = true;
            LOG.warn("Molecule material associations are retained but inactive: {}. Item/fluid associations and standalone rendering remain available.", unavailableReason);
        }
    }

    /**
     * Normalizes a material resource location identifier, converting modern gtceu aliases to gregtech equivalents.
     *
     * @param id input resource location
     * @return normalized resource location
     */
    public static ResourceLocation normalizeMaterialId(ResourceLocation id) {
        String name = id.toString();
        return name.startsWith("gtceu:") ? new ResourceLocation(MODID + ":" + name.substring(6)) : id;
    }

    /**
     * Retrieves the GregTech material identifier associated with an item stack.
     *
     * @param stack item stack instance
     * @return material resource location, or {@code null} if unavailable
     */
    public static ResourceLocation materialId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        Adapter active = activeAdapter();
        if (active == null) return null;
        try {
            Object materialStack = active.itemMaterial.invoke(null, stack);
            return materialStack == null ? null : active.id(active.materialField.get(materialStack));
        } catch (ReflectiveOperationException | LinkageError ex) {
            disable(ex);
            return null;
        }
    }

    /**
     * Retrieves the GregTech material identifier associated with a fluid.
     *
     * @param fluid fluid instance
     * @return material resource location, or {@code null} if unavailable
     */
    public static ResourceLocation materialId(Fluid fluid) {
        if (fluid == null) return null;
        Adapter active = activeAdapter();
        if (active == null) return null;
        try {
            return active.id(active.fluidMaterial.invoke(null, fluid));
        } catch (ReflectiveOperationException | LinkageError ex) {
            disable(ex);
            return null;
        }
    }

    /** Hover lookups avoid the initialization monitor and retain a stable adapter for each call. */
    private static Adapter activeAdapter() {
        if (!initialized) initialize();
        return adapter;
    }

    /** Initializes the reflection adapter discovery process. */
    private static synchronized void initialize() {
        if (initialized) return;
        try {
            adapter = discoverAdapter();
        } catch (ReflectiveOperationException | LinkageError ex) {
            disable(ex);
        } finally {
            // Publish completion only after both the adapter and its diagnostic are ready.
            initialized = true;
        }
    }

    /**
     * Discovers and reflects upon GregTech classes to construct an operational compatibility adapter.
     *
     * @return discovered {@link Adapter} instance, or {@code null} if unsupported/missing
     * @throws ReflectiveOperationException if reflection lookup fails
     */
    private static Adapter discoverAdapter() throws ReflectiveOperationException {
        // Avoid creating Forge's Loader in standalone parser/matching tests. This is also
        // the common production path for the library without the optional integration.
        ClassLoader loader = GTCompat.class.getClassLoader();
        if (loader.getResource("gregtech/api/unification/OreDictUnifier.class") == null) return null;
        ModContainer mod = Loader.instance().getIndexedModList().get(MODID);
        if (mod == null) return null;
        if (!SUPPORTED_VERSION.equals(mod.getVersion())) {
            unavailableReason = "installed gregtech version " + mod.getVersion() + " is not the verified " + SUPPORTED_VERSION + " adapter target";
            return null;
        }
        Class<?> unifier = Class.forName("gregtech.api.unification.OreDictUnifier", false, loader);
        Class<?> fluidUnifier = Class.forName("gregtech.api.unification.FluidUnifier", false, loader);
        Class<?> stack = Class.forName("gregtech.api.unification.stack.MaterialStack", false, loader);
        Class<?> material = Class.forName("gregtech.api.unification.material.Material", false, loader);
        Adapter discovered = new Adapter(unifier.getMethod("getMaterial", ItemStack.class),
                fluidUnifier.getMethod("getMaterialFromFluid", Fluid.class),
                stack.getField("material"), material.getMethod("getResourceLocation"));
        LOG.info("Enabled optional GregTechCEu {} material adapter (gtceu:* aliases gregtech:*).", SUPPORTED_VERSION);
        return discovered;
    }

    /**
     * Disables the adapter due to an exception and records the unavailability reason.
     *
     * @param ex caught exception or error
     */
    private static synchronized void disable(Throwable ex) {
        adapter = null;
        unavailableReason = "the pinned GregTechCEu API could not be invoked: " + ex;
        if (!warned) {
            warned = true;
            LOG.error("Disabling optional GregTech material matching; molecule definitions remain available", ex);
        }
    }

    /**
     * Record holding reflected methods and fields for GregTech material interactions.
     *
     * @param itemMaterial     reflected method to get material from item stack
     * @param fluidMaterial    reflected method to get material from fluid
     * @param materialField    reflected field for material stack material reference
     * @param resourceLocation reflected method to get resource location from material
     */
    private record Adapter(Method itemMaterial, Method fluidMaterial, Field materialField, Method resourceLocation) {

        /**
         * Invokes the resource location method on a material object.
         *
         * @param material material object instance
         * @return material resource location, or {@code null}
         * @throws ReflectiveOperationException if invocation fails
         */
        ResourceLocation id(Object material) throws ReflectiveOperationException {
            return material == null ? null : (ResourceLocation) resourceLocation.invoke(material);
        }
    }
}
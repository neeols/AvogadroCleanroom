package io.github.wurtzitane.avogadro.molecule;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import io.github.wurtzitane.avogadro.api.MoleculeView;
import io.github.wurtzitane.avogadro.compat.gt.GTCompat;

import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;
import net.minecraftforge.fluids.capability.IFluidTankProperties;

/** Client-visible molecule snapshot. Contains no client classes and is safe to load on a server. */
public final class MoleculeStore {

    /** Default resource location identifier for vanilla coal. */
    private static final ResourceLocation COAL = new ResourceLocation("minecraft:coal");

    /** Default resource location identifier for vanilla charcoal. */
    private static final ResourceLocation CHARCOAL = new ResourceLocation("minecraft:charcoal");

    /** Default fallback item tags map containing baseline coal grouping. */
    private static final Map<ResourceLocation, List<String>> DEFAULT_ITEM_TAGS = Map
            .of(new ResourceLocation("minecraft:coals"), List.of("minecraft:coal"));

    /** Default fallback fluid tags map containing baseline water and lava fluid tags. */
    private static final Map<ResourceLocation, List<String>> DEFAULT_FLUID_TAGS = Map.of(
            new ResourceLocation("minecraft:water"), List.of("minecraft:water"), new ResourceLocation("minecraft:lava"),
            List.of("minecraft:lava"));

    /**
     * Represents a single tag association mapping a tag resource location to a molecule.
     *
     * @param tag tag resource location identifier
     * @param molecule molecule resource location identifier
     */
    private record TagRow(ResourceLocation tag, ResourceLocation molecule) {
    }

    /**
     * Represents an entry within a tag dictionary, indicating whether it references another tag or an item/fluid.
     *
     * @param id resource location identifier
     * @param reference {@code true} if a nested tag reference, {@code false} if a direct target
     */
    private record TagEntry(ResourceLocation id, boolean reference) {
    }

    /**
     * Represents an NBT-conditioned item binding rule with specificity and registration order priority.
     *
     * @param association item association with required NBT predicate
     * @param molecule molecule resource location identifier
     * @param specificity calculated specificity integer score
     * @param order registration order sequence integer
     */
    private record NbtBinding(ItemAssociation association, ResourceLocation molecule, int specificity, int order) {
    }

    /**
     * Represents an NBT-conditioned fluid binding rule with specificity and registration order priority.
     *
     * @param association fluid association with required NBT predicate
     * @param molecule molecule resource location identifier
     * @param specificity calculated specificity integer score
     * @param order registration order sequence integer
     */
    private record FluidNbtBinding(FluidAssociation association, ResourceLocation molecule, int specificity,
            int order) {
    }

    /**
     * Container record for item and fluid tag dictionaries.
     *
     * @param items map of item tag resource locations to tag entries
     * @param fluids map of fluid tag resource locations to tag entries
     */
    private record Tags(Map<ResourceLocation, List<TagEntry>> items, Map<ResourceLocation, List<TagEntry>> fluids) {
    }

    /**
     * Immutable snapshot structure storing compiled definitions, indexed lookups, tags, and dictionaries.
     *
     * @param definitions map of molecule resource locations to molecule definitions
     * @param all unmodifiable list of all molecule definitions
     * @param items map of item selectors to molecule resource locations
     * @param nbtItems map of item resource locations to sorted NBT bindings
     * @param itemTags resolved item tag associations map
     * @param fluids map of fluid resource locations to molecule resource locations
     * @param nbtFluids map of fluid resource locations to sorted NBT fluid bindings
     * @param fluidTags resolved fluid tag associations map
     * @param materials map of material resource locations to molecule resource locations
     * @param paths map of resource path strings to molecule resource locations
     * @param tags underlying tag dictionary container
     */
    private record Snapshot(Map<ResourceLocation, MoleculeDef> definitions, List<MoleculeDef> all,
            Map<String, ResourceLocation> items, Map<ResourceLocation, List<NbtBinding>> nbtItems,
            Map<ResourceLocation, ResourceLocation> itemTags, Map<ResourceLocation, ResourceLocation> fluids,
            Map<ResourceLocation, List<FluidNbtBinding>> nbtFluids, Map<ResourceLocation, ResourceLocation> fluidTags,
            Map<ResourceLocation, ResourceLocation> materials, Map<String, ResourceLocation> paths, Tags tags) {
    }

    /** Volatile active snapshot instance. */
    private static volatile Snapshot snapshot = build(Map.of(), tags(Map.of(), Map.of()));

    /** Atomic revision counter tracking snapshot updates. */
    private static final AtomicLong REVISION = new AtomicLong();

    /** Volatile molecule view instance representing the current store state. */
    private static volatile MoleculeView view = MoleculeView.empty();

    private MoleculeStore() {}

    /**
     * Returns a molecule definition by its resource location identifier.
     *
     * @param id molecule resource location identifier
     * @return matching {@link MoleculeDef}, or {@code null} if not found
     */
    public static MoleculeDef get(ResourceLocation id) {
        return snapshot.definitions.get(id);
    }

    /**
     * Returns a collection of all registered molecule definitions.
     *
     * @return collection of all {@link MoleculeDef} instances
     */
    public static Collection<MoleculeDef> all() {
        return snapshot.all;
    }

    /**
     * Replaces all molecule definitions in the store and publishes an updated snapshot.
     *
     * @param definitions map of new molecule definitions
     */
    public static synchronized void replaceAll(Map<ResourceLocation, MoleculeDef> definitions) {
        publish(build(definitions, snapshot.tags));
    }

    /**
     * Replaces definitions and their dictionaries together, so readers see one complete reload.
     *
     * @param definitions map of new molecule definitions
     * @param itemTags map of item tag definitions
     * @param fluidTags map of fluid tag definitions
     */
    public static synchronized void replaceAll(Map<ResourceLocation, MoleculeDef> definitions,
            Map<ResourceLocation, List<String>> itemTags, Map<ResourceLocation, List<String>> fluidTags) {
        publish(build(definitions, tags(itemTags, fluidTags)));
    }

    /**
     * Publishes an already compiled immutable snapshot without rebuilding its indexes.
     *
     * @param compiled compiled snapshot instance to publish
     */
    public static synchronized void replaceAll(CompiledSnapshot compiled) {
        publish(java.util.Objects.requireNonNull(compiled, "compiled").data);
    }

    /**
     * Publishes a new snapshot, validating material availability, incrementing revision counters, and updating views.
     *
     * @param next target snapshot to publish
     */
    private static void publish(Snapshot next) {
        snapshot = next;
        Collection<MoleculeDef> definitions = next.definitions.values();
        if (definitions.stream().anyMatch(def -> !def.materials().isEmpty()))
            GTCompat.warnIfUnavailable();
        long revision = REVISION.incrementAndGet();
        view = new MoleculeView(new CompiledSnapshot(next), revision);
    }

    /**
     * Returns the current active molecule view instance.
     *
     * @return current {@link MoleculeView}
     */
    public static MoleculeView view() {
        return view;
    }

    /** Matching compiled against one immutable snapshot, used by retained API views. */
    public interface Lookup {

        /**
         * Looks up a molecule identifier for an item stack.
         *
         * @param stack item stack instance
         * @return matching resource location identifier, or {@code null}
         */
        ResourceLocation item(ItemStack stack);

        /**
         * Looks up a molecule identifier for a fluid instance.
         *
         * @param fluid fluid instance
         * @return matching resource location identifier, or {@code null}
         */
        ResourceLocation fluid(Fluid fluid);

        /**
         * Stack-aware lookup; old custom implementations retain their unqualified behavior.
         *
         * @param stack fluid stack instance
         * @return matching resource location identifier, or {@code null}
         */
        default ResourceLocation fluid(FluidStack stack) {
            return stack == null || stack.amount <= 0 ? null : fluid(stack.getFluid());
        }
    }

    /** Immutable matching data; only this store can construct a trusted compiled snapshot. */
    public static final class CompiledSnapshot implements Lookup {

        /** Underlying snapshot data. */
        private final Snapshot data;

        /** Mapped item tags. */
        private final Map<ResourceLocation, List<String>> itemTags;

        /** Mapped fluid tags. */
        private final Map<ResourceLocation, List<String>> fluidTags;

        /**
         * Constructs a compiled snapshot from raw snapshot data.
         *
         * @param data snapshot data instance
         */
        private CompiledSnapshot(Snapshot data) {
            this(data, rawTags(data.tags.items), rawTags(data.tags.fluids));
        }

        /**
         * Constructs a compiled snapshot with explicit snapshot data and tag maps.
         *
         * @param data snapshot data instance
         * @param itemTags item tags map
         * @param fluidTags fluid tags map
         */
        private CompiledSnapshot(Snapshot data, Map<ResourceLocation, List<String>> itemTags,
                Map<ResourceLocation, List<String>> fluidTags) {
            this.data = data;
            this.itemTags = itemTags;
            this.fluidTags = fluidTags;
        }

        /**
         * Returns the definitions map.
         *
         * @return map of resource locations to molecule definitions
         */
        public Map<ResourceLocation, MoleculeDef> definitions() {
            return data.definitions;
        }

        /**
         * Returns the list of all definitions.
         *
         * @return list of {@link MoleculeDef} instances
         */
        public List<MoleculeDef> all() {
            return data.all;
        }

        /**
         * Returns the item tags map.
         *
         * @return item tags map
         */
        public Map<ResourceLocation, List<String>> itemTags() {
            return itemTags;
        }

        /**
         * Returns the fluid tags map.
         *
         * @return fluid tags map
         */
        public Map<ResourceLocation, List<String>> fluidTags() {
            return fluidTags;
        }

        /**
         * Looks up an item stack against this compiled snapshot.
         *
         * @param stack item stack instance
         * @return matching resource location, or {@code null}
         */
        @Override
        public ResourceLocation item(ItemStack stack) {
            return lookup(stack, data);
        }

        /**
         * Direct and dictionary bindings only, for callers with an item ID rather than a live stack.
         *
         * @param item item resource location
         * @param metadata metadata integer
         * @param nbt compound NBT tag
         * @return matching resource location, or {@code null}
         */
        public ResourceLocation item(ResourceLocation item, int metadata, NBTTagCompound nbt) {
            return itemBinding(item, metadata, nbt, data);
        }

        /**
         * Looks up a fluid against this compiled snapshot.
         *
         * @param fluid fluid instance
         * @return matching resource location, or {@code null}
         */
        @Override
        public ResourceLocation fluid(Fluid fluid) {
            return lookup(fluid, data);
        }

        /**
         * Looks up a fluid stack against this compiled snapshot.
         *
         * @param fluid fluid stack instance
         * @return matching resource location, or {@code null}
         */
        @Override
        public ResourceLocation fluid(FluidStack fluid) {
            return lookup(fluid, data);
        }

        /**
         * Direct and dictionary bindings only; a null compound means an untagged stack.
         *
         * @param fluid fluid resource location
         * @param nbt compound NBT tag
         * @return matching resource location, or {@code null}
         */
        public ResourceLocation fluid(ResourceLocation fluid, NBTTagCompound nbt) {
            return fluidBinding(fluid, nbt, data);
        }
    }

    /**
     * Compiles a snapshot from definitions and tag dictionaries.
     *
     * @param definitions map of molecule definitions
     * @param items map of item tags
     * @param fluids map of fluid tags
     * @return constructed {@link CompiledSnapshot}
     */
    public static CompiledSnapshot compileSnapshot(Map<ResourceLocation, MoleculeDef> definitions,
            Map<ResourceLocation, List<String>> items, Map<ResourceLocation, List<String>> fluids) {
        return new CompiledSnapshot(build(definitions, tags(items, fluids)), immutableTags(items),
                immutableTags(fluids));
    }

    /**
     * Creates an immutable copy of a tag map.
     *
     * @param source source tag map
     * @return unmodifiable tag map
     */
    private static Map<ResourceLocation, List<String>> immutableTags(Map<ResourceLocation, List<String>> source) {
        Map<ResourceLocation, List<String>> result = new LinkedHashMap<>();
        source.forEach((id, entries) -> result.put(id, List.copyOf(entries)));
        return Collections.unmodifiableMap(result);
    }

    /**
     * Compiles a lookup instance from definitions and tags.
     *
     * @param definitions map of molecule definitions
     * @param items map of item tags
     * @param fluids map of fluid tags
     * @return compiled {@link Lookup}
     */
    public static Lookup compileLookup(Map<ResourceLocation, MoleculeDef> definitions,
            Map<ResourceLocation, List<String>> items, Map<ResourceLocation, List<String>> fluids) {
        return compileSnapshot(definitions, items, fluids);
    }

    /**
     * Converts raw tag entries into raw string representations.
     *
     * @param tags map of tag entries
     * @return map of resource locations to tag string lists
     */
    private static Map<ResourceLocation, List<String>> rawTags(Map<ResourceLocation, List<TagEntry>> tags) {
        Map<ResourceLocation, List<String>> result = new LinkedHashMap<>();
        tags.forEach((id, entries) -> result.put(id,
                entries.stream().map(entry -> (entry.reference ? "#" : "") + entry.id).toList()));
        return Collections.unmodifiableMap(result);
    }

    /**
     * Returns the current snapshot revision number.
     *
     * @return revision long value
     */
    public static long revision() {
        return REVISION.get();
    }

    /**
     * Converts the two existing modern vanilla associations that changed representation in 1.12.
     *
     * @param name association string selector
     * @return constructed item stack instance
     */
    public static ItemStack itemForAssociation(String name) {
        ItemAssociation association = normalizedNbt(ItemAssociation.parse(name));
        int metadata = association.metadata() == null ? 0 : association.metadata();
        ResourceLocation id = association.item();
        Item item = Item.REGISTRY.getObject(id);
        if (item == null || item == Items.AIR)
            return ItemStack.EMPTY;
        ItemStack stack = new ItemStack(item, 1, metadata);
        if (association.hasNbt())
            stack.setTagCompound(association.requiredNbt());
        return stack;
    }

    /**
     * Normalizes item association strings, adapting legacy block mappings.
     *
     * @param value association string
     * @return normalized association string
     */
    private static String normalizeItemAssociation(String value) {
        ItemAssociation association = ItemAssociation.parse(value);
        ResourceLocation item = association.item().equals(new ResourceLocation("minecraft:snow_block"))
                ? new ResourceLocation("minecraft:snow")
                : association.item();
        return item + (association.metadata() == null ? "" : "@" + association.metadata());
    }

    /**
     * Normalizes item association NBT and material mappings.
     *
     * @param association input item association
     * @return normalized {@link ItemAssociation}
     */
    private static ItemAssociation normalizedNbt(ItemAssociation association) {
        if (association.item().equals(CHARCOAL))
            return new ItemAssociation(COAL, 1, association.requiredNbt());
        if (association.item().equals(new ResourceLocation("minecraft:snow_block")))
            return new ItemAssociation(new ResourceLocation("minecraft:snow"), association.metadata(),
                    association.requiredNbt());
        return association;
    }

    /**
     * All item forms represented by direct and tag associations, including registry-wide subtypes.
     *
     * @param definition molecule definition instance
     * @return list of associated {@link ItemStack} instances
     */
    public static List<ItemStack> associatedItems(MoleculeDef definition) {
        List<ItemStack> result = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<String> names = new java.util.ArrayList<>(definition.items());
        for (var entry : snapshot.itemTags.entrySet())
            if (entry.getValue().equals(definition.id()))
                names.add(entry.getKey().toString());
        for (String name : names) {
            ItemAssociation association = ItemAssociation.parse(name);
            ItemStack first = itemForAssociation(name);
            if (first.isEmpty())
                continue;
            net.minecraft.util.NonNullList<ItemStack> variants = net.minecraft.util.NonNullList.create();
            if (association.metadata() == null && !association.item().equals(CHARCOAL)
                    && first.getItem().getHasSubtypes())
                first.getItem().getSubItems(net.minecraft.creativetab.CreativeTabs.SEARCH, variants);
            if (variants.isEmpty())
                variants.add(first);
            for (ItemStack stack : variants) {
                if (stack.isEmpty())
                    continue;
                if (association.hasNbt()) {
                    stack = stack.copy();
                    NBTTagCompound tag = stack.getTagCompound() == null
                            ? new NBTTagCompound()
                            : stack.getTagCompound().copy();
                    tag.merge(association.requiredNbt());
                    stack.setTagCompound(tag);
                }
                String key = stack.getItem().getRegistryName() + "@" + stack.getMetadata() + ":"
                        + stack.getTagCompound();
                if (!stack.isEmpty() && seen.add(key))
                    result.add(stack.copy());
            }
        }
        return List.copyOf(result);
    }

    /**
     * Returns all associated fluid stacks for a molecule definition.
     *
     * @param definition molecule definition instance
     * @return list of associated {@link FluidStack} instances
     */
    public static List<FluidStack> associatedFluids(MoleculeDef definition) {
        java.util.Set<String> names = new java.util.LinkedHashSet<>(definition.fluids());
        for (var entry : snapshot.fluidTags.entrySet())
            if (entry.getValue().equals(definition.id()))
                names.add(entry.getKey().toString());
        List<FluidStack> result = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String name : names) {
            FluidAssociation association = FluidAssociation.parse(name);
            String registryName = association.fluid().toString();
            Fluid fluid = net.minecraftforge.fluids.FluidRegistry.getFluid(registryName);
            if (fluid == null && registryName.startsWith("minecraft:"))
                fluid = net.minecraftforge.fluids.FluidRegistry.getFluid(registryName.substring(10));
            if (fluid != null && seen.add(association.selector()))
                result.add(new FluidStack(fluid, 1000, association.requiredNbt()));
        }
        return List.copyOf(result);
    }

    /**
     * Sets all definitions in the store.
     *
     * @param definitions map of molecule definitions
     */
    public static void setAll(Map<ResourceLocation, MoleculeDef> definitions) {
        replaceAll(definitions);
    }

    /**
     * Narrow tag dictionaries: registry names and #tag references; there is no datapack engine.
     *
     * @param items map of item tags
     * @param fluids map of fluid tags
     */
    public static synchronized void setTags(Map<ResourceLocation, List<String>> items,
            Map<ResourceLocation, List<String>> fluids) {
        publish(build(snapshot.definitions, tags(items, fluids)));
    }

    /**
     * Looks up a molecule identifier for an item stack against the active snapshot.
     *
     * @param stack item stack instance
     * @return matching resource location identifier, or {@code null}
     */
    public static ResourceLocation lookup(ItemStack stack) {
        return lookup(stack, snapshot);
    }

    /**
     * Looks up an item stack against a specific snapshot instance.
     *
     * @param stack item stack instance
     * @param data snapshot instance
     * @return matching resource location identifier, or {@code null}
     */
    private static ResourceLocation lookup(ItemStack stack, Snapshot data) {
        if (stack == null || stack.isEmpty())
            return null;
        if (stack.getItem() == io.github.wurtzitane.avogadro.item.MoleculeItems.MODEL) {
            ResourceLocation id = io.github.wurtzitane.avogadro.item.MoleculeItems.id(stack);
            MoleculeDef definition = data.definitions.get(id);
            return definition != null && definition.generateItem() ? id : null;
        }
        ResourceLocation key = stack.getItem().getRegistryName();
        if (key == null)
            return null;
        ResourceLocation binding = itemBinding(key, stack.getMetadata(), stack.getTagCompound(), data);
        if (binding != null)
            return binding;

        // Item capabilities are distinct from block capabilities in Forge 1.12.2.
        IFluidHandlerItem handler = stack.getCapability(CapabilityFluidHandler.FLUID_HANDLER_ITEM_CAPABILITY, null);
        if (handler != null) {
            for (IFluidTankProperties tank : handler.getTankProperties()) {
                if (tank == null)
                    continue;
                FluidStack contents = tank.getContents();
                if (contents == null || contents.amount <= 0)
                    continue;
                ResourceLocation hit = lookup(contents, data);
                MoleculeDef definition = hit == null ? null : data.definitions.get(hit);
                if (definition != null && definition.matchContainers())
                    return hit;
            }
        }
        if (GTCompat.isLoaded()) {
            ResourceLocation material = GTCompat.materialId(stack);
            ResourceLocation hit = material(data, material);
            if (hit != null)
                return hit;
        }
        return data.paths.get(key.getPath());
    }

    /**
     * Resolves an item binding rule against snapshot data given registry keys, metadata, and NBT.
     *
     * @param key item registry resource location
     * @param metadata metadata integer
     * @param nbt compound NBT tag
     * @param data snapshot instance
     * @return matching resource location, or {@code null}
     */
    private static ResourceLocation itemBinding(ResourceLocation key, int metadata, NBTTagCompound nbt, Snapshot data) {
        for (NbtBinding binding : data.nbtItems.getOrDefault(key, List.of()))
            if (binding.association.matches(metadata, nbt))
                return binding.molecule;
        ResourceLocation exact = data.items.get(key + "@" + metadata);
        if (exact != null)
            return exact;
        if (COAL.equals(key) && metadata == 1) {
            ResourceLocation charcoal = data.items.get(CHARCOAL.toString());
            if (charcoal != null)
                return charcoal;
        }
        ResourceLocation direct = data.items.get(key.toString());
        if (direct != null)
            return direct;
        ResourceLocation tagged = data.itemTags.get(key);
        if (tagged != null)
            return tagged;
        return null;
    }

    /**
     * Looks up a molecule identifier for a fluid stack against the active snapshot.
     *
     * @param stack fluid stack instance
     * @return matching resource location identifier, or {@code null}
     */
    public static ResourceLocation lookup(FluidStack stack) {
        return lookup(stack, snapshot);
    }

    /**
     * Looks up a fluid stack against a specific snapshot instance.
     *
     * @param stack fluid stack instance
     * @param data snapshot instance
     * @return matching resource location identifier, or {@code null}
     */
    private static ResourceLocation lookup(FluidStack stack, Snapshot data) {
        if (stack == null || stack.amount <= 0 || stack.getFluid() == null)
            return null;
        ResourceLocation hit = fluidBinding(new ResourceLocation(stack.getFluid().getName()), stack.tag, data);
        return hit != null ? hit : lookup(stack.getFluid(), data);
    }

    /**
     * Resolves a fluid NBT binding rule against snapshot data.
     *
     * @param key fluid registry resource location
     * @param nbt compound NBT tag
     * @param data snapshot instance
     * @return matching resource location, or {@code null}
     */
    private static ResourceLocation fluidBinding(ResourceLocation key, NBTTagCompound nbt, Snapshot data) {
        for (FluidNbtBinding binding : data.nbtFluids.getOrDefault(key, List.of()))
            if (binding.association.matches(nbt))
                return binding.molecule;
        ResourceLocation direct = data.fluids.get(key);
        return direct != null ? direct : data.fluidTags.get(key);
    }

    /**
     * Looks up a molecule identifier for a fluid instance against the active snapshot.
     *
     * @param fluid fluid instance
     * @return matching resource location identifier, or {@code null}
     */
    public static ResourceLocation lookup(Fluid fluid) {
        return fluid == null ? null : lookup(fluid, snapshot);
    }

    /**
     * Looks up a fluid against a specific snapshot instance.
     *
     * @param fluid fluid instance
     * @param data snapshot instance
     * @return matching resource location identifier, or {@code null}
     */
    private static ResourceLocation lookup(Fluid fluid, Snapshot data) {
        if (fluid == null)
            return null;
        // The 1.12 FluidRegistry uses String names. Unqualified names use Minecraft's default namespace.
        ResourceLocation key = new ResourceLocation(fluid.getName());
        ResourceLocation direct = data.fluids.get(key);
        if (direct != null)
            return direct;
        ResourceLocation tagged = data.fluidTags.get(key);
        if (tagged != null)
            return tagged;
        if (GTCompat.isLoaded()) {
            ResourceLocation hit = material(data, GTCompat.materialId(fluid));
            if (hit != null)
                return hit;
        }
        return data.paths.get(key.getPath());
    }

    /**
     * Looks up a material resource location against snapshot data.
     *
     * @param data snapshot instance
     * @param material material resource location
     * @return matching resource location, or {@code null}
     */
    private static ResourceLocation material(Snapshot data, ResourceLocation material) {
        if (material == null)
            return null;
        ResourceLocation hit = data.materials.get(material);
        return hit != null ? hit : data.paths.get(material.getPath());
    }

    /**
     * Builds a compiled immutable snapshot from molecule definitions and tags.
     *
     * @param definitions map of molecule definitions
     * @param tags tags instance container
     * @return constructed {@link Snapshot}
     */
    private static Snapshot build(Map<ResourceLocation, MoleculeDef> definitions, Tags tags) {
        Map<ResourceLocation, MoleculeDef> defs = new LinkedHashMap<>(definitions);
        Map<String, ResourceLocation> items = new HashMap<>();
        Map<ResourceLocation, List<NbtBinding>> nbtItems = new HashMap<>();
        int order = 0;
        Map<ResourceLocation, ResourceLocation> fluids = new HashMap<>();
        Map<ResourceLocation, List<FluidNbtBinding>> nbtFluids = new HashMap<>();
        Map<ResourceLocation, ResourceLocation> materials = new HashMap<>();
        List<TagRow> itemTags = new ArrayList<>();
        List<TagRow> fluidTags = new ArrayList<>();
        Map<String, ResourceLocation> paths = new HashMap<>();
        for (Map.Entry<ResourceLocation, MoleculeDef> entry : defs.entrySet()) {
            ResourceLocation id = entry.getKey();
            MoleculeDef def = entry.getValue();
            for (String value : def.items()) {
                ItemAssociation association = ItemAssociation.parse(value);
                if (association.hasNbt()) {
                    association = normalizedNbt(association);
                    nbtItems.computeIfAbsent(association.item(), ignored -> new ArrayList<>())
                            .add(new NbtBinding(association, id, specificity(association.requiredNbt()), order++));
                } else
                    items.put(normalizeItemAssociation(value), id);
            }
            for (String value : def.fluids()) {
                FluidAssociation association = FluidAssociation.parse(value);
                if (association.hasNbt())
                    nbtFluids.computeIfAbsent(association.fluid(), ignored -> new ArrayList<>())
                            .add(new FluidNbtBinding(association, id, specificity(association.requiredNbt()), order++));
                else
                    fluids.put(association.fluid(), id);
            }
            for (String value : def.materials())
                materials.put(GTCompat.normalizeMaterialId(new ResourceLocation(value)), id);
            for (String value : def.itemTags())
                itemTags.add(new TagRow(new ResourceLocation(value), id));
            for (String value : def.fluidTags())
                fluidTags.add(new TagRow(new ResourceLocation(value), id));
            paths.put(id.getPath(), id);
        }
        Map<ResourceLocation, List<NbtBinding>> indexedNbt = new HashMap<>();
        var priority = java.util.Comparator.<NbtBinding>comparingInt(b -> b.association.metadata() == null ? 0 : 1)
                .thenComparingInt(NbtBinding::specificity).thenComparingInt(NbtBinding::order).reversed();
        nbtItems.forEach((item, bindings) -> indexedNbt.put(item, bindings.stream().sorted(priority).toList()));
        Map<ResourceLocation, List<FluidNbtBinding>> indexedFluidNbt = new HashMap<>();
        var fluidPriority = java.util.Comparator.comparingInt(FluidNbtBinding::specificity)
                .thenComparingInt(FluidNbtBinding::order).reversed();
        nbtFluids.forEach(
                (fluid, bindings) -> indexedFluidNbt.put(fluid, bindings.stream().sorted(fluidPriority).toList()));
        return new Snapshot(Collections.unmodifiableMap(defs), List.copyOf(defs.values()), Map.copyOf(items),
                Map.copyOf(indexedNbt), tagAssociations(tags.items, itemTags), Map.copyOf(fluids),
                Map.copyOf(indexedFluidNbt), tagAssociations(tags.fluids, fluidTags), Map.copyOf(materials),
                Map.copyOf(paths), tags);
    }

    /**
     * Computes the structural specificity score of an NBT base tag for prioritization.
     *
     * @param tag NBT base instance
     * @return calculated specificity integer score
     */
    private static int specificity(net.minecraft.nbt.NBTBase tag) {
        if (tag instanceof NBTTagCompound compound) {
            int count = 1;
            for (String key : compound.getKeySet())
                count += specificity(compound.getTag(key));
            return count;
        }
        if (tag instanceof net.minecraft.nbt.NBTTagList list) {
            int count = 1;
            for (int i = 0; i < list.tagCount(); i++)
                count += specificity(list.get(i));
            return count;
        }
        return 1;
    }

    /**
     * Merges default tag maps with user input tag maps.
     *
     * @param items item tags map
     * @param fluids fluid tags map
     * @return combined {@link Tags} container
     */
    private static Tags tags(Map<ResourceLocation, List<String>> items, Map<ResourceLocation, List<String>> fluids) {
        return new Tags(tagDictionary(DEFAULT_ITEM_TAGS, items), tagDictionary(DEFAULT_FLUID_TAGS, fluids));
    }

    /**
     * Compiles a tag dictionary from default values and user input.
     *
     * @param defaults default tag mapping
     * @param input user input tag mapping
     * @return compiled tag entry map
     */
    private static Map<ResourceLocation, List<TagEntry>> tagDictionary(Map<ResourceLocation, List<String>> defaults,
            Map<ResourceLocation, List<String>> input) {
        Map<ResourceLocation, List<String>> merged = new LinkedHashMap<>(defaults);
        merged.putAll(input);
        Map<ResourceLocation, List<TagEntry>> result = new HashMap<>();
        merged.forEach((id, values) -> {
            List<TagEntry> entries = new ArrayList<>(values.size());
            for (String value : values) {
                boolean reference = value.startsWith("#");
                entries.add(new TagEntry(MoleculeLoader.identifier(reference ? value.substring(1) : value), reference));
            }
            result.put(id, List.copyOf(entries));
        });
        return Map.copyOf(result);
    }

    /**
     * Resolves tag association mappings recursively through tag dictionary hierarchies.
     *
     * @param dictionary tag entry dictionary map
     * @param rows list of tag association rows
     * @return resolved tag association map
     */
    private static Map<ResourceLocation, ResourceLocation> tagAssociations(
            Map<ResourceLocation, List<TagEntry>> dictionary, List<TagRow> rows) {
        Map<ResourceLocation, ResourceLocation> result = new HashMap<>();
        Set<ResourceLocation> visited = new HashSet<>();
        ArrayDeque<ResourceLocation> pending = new ArrayDeque<>();
        // Earlier tag bindings win. A shared subtree already visited for an earlier
        // binding cannot add a later match, so each dictionary entry is walked once.
        for (TagRow row : rows) {
            pending.add(row.tag);
            while (!pending.isEmpty()) {
                ResourceLocation next = pending.removeLast();
                if (!visited.add(next))
                    continue;
                for (TagEntry entry : dictionary.getOrDefault(next, List.of())) {
                    if (entry.reference) {
                        pending.add(entry.id);
                    } else {
                        result.putIfAbsent(entry.id, row.molecule);
                    }
                }
            }
        }
        return Map.copyOf(result);
    }
}

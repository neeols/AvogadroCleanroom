package io.github.wurtzitane.avogadro.item;

import java.util.*;

import io.github.wurtzitane.avogadro.Avogadro;
import io.github.wurtzitane.avogadro.api.MoleculeClassification;
import io.github.wurtzitane.avogadro.api.MoleculeView;
import io.github.wurtzitane.avogadro.molecule.MoleculeDef;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/** One permanent registry entry; molecule identity is saved as an ID, never as an ordinal. */
@Mod.EventBusSubscriber(modid = Avogadro.MODID)
public final class MoleculeItems {

    /** NBT tag key for storing molecule identifiers on item stacks. */
    public static final String ID_TAG = "avogadro_molecule";

    /** Main model item instance representing registered molecules. */
    public static final Item MODEL = new ModelItem().setRegistryName(Avogadro.MODID, "molecule")
            .setTranslationKey("avogadro.molecule").setHasSubtypes(true);

    /** Artwork-only tab icon: no molecule identity or generated-item variant. */
    public static final Item CATEGORY_ICON = new Item() {

        @Override
        public void getSubItems(CreativeTabs tab, NonNullList<ItemStack> items) {}
    }.setRegistryName(Avogadro.MODID, "molecules_icon").setTranslationKey("avogadro.molecules_icon");

    /** Main creative tab for Avogadro molecules. */
    public static final CreativeTabs TAB = new CreativeTabs("avogadro.molecules") {

        @Override
        public ItemStack createIcon() {
            return new ItemStack(CATEGORY_ICON);
        }

        @Override
        public void displayAllRelevantItems(NonNullList<ItemStack> items) {
            if (ELEMENTS_TAB == null && io.github.wurtzitane.avogadro.Config.PERIODIC_TABLE_CRAFTING.get())
                items.add(PeriodicTableItem.stack());
            variants(Avogadro.proxy.moleculeItemView(), MoleculeClassification.MOLECULE).forEach(items::add);
        }
    };

    /** Optional creative tab for chemical elements. */
    public static CreativeTabs ELEMENTS_TAB;

    /** Optional creative tab for crystal structures. */
    public static CreativeTabs CRYSTALS_TAB;

    /** Optional creative tab for polymer structures. */
    public static CreativeTabs POLYMERS_TAB;

    private MoleculeItems() {}

    /** Native tabs are created at startup; changing the split preference takes effect after restart. */
    public static void initializeTabs() {
        if (POLYMERS_TAB == null && io.github.wurtzitane.avogadro.Config.SPLIT_POLYMER_TAB.get()) {
            POLYMERS_TAB = new CreativeTabs("avogadro.polymers") {

                @Override
                public ItemStack createIcon() {
                    var samples = variants(Avogadro.proxy.moleculeItemView(), MoleculeClassification.POLYMER);
                    return samples.isEmpty() ? new ItemStack(CATEGORY_ICON) : samples.getFirst();
                }

                @Override
                public void displayAllRelevantItems(NonNullList<ItemStack> items) {
                    variants(Avogadro.proxy.moleculeItemView(), MoleculeClassification.POLYMER).forEach(items::add);
                }
            };
        }
        if (CRYSTALS_TAB == null && io.github.wurtzitane.avogadro.Config.SPLIT_CRYSTAL_TAB.get()) {
            CRYSTALS_TAB = new CreativeTabs("avogadro.crystals") {

                @Override
                public ItemStack createIcon() {
                    var samples = variants(Avogadro.proxy.moleculeItemView(), MoleculeClassification.CRYSTAL);
                    return samples.isEmpty() ? new ItemStack(CATEGORY_ICON) : samples.getFirst();
                }

                @Override
                public void displayAllRelevantItems(NonNullList<ItemStack> items) {
                    variants(Avogadro.proxy.moleculeItemView(), MoleculeClassification.CRYSTAL).forEach(items::add);
                }
            };
        }
        if (ELEMENTS_TAB != null || !io.github.wurtzitane.avogadro.Config.SPLIT_CREATIVE_TABS.get())
            return;
        ELEMENTS_TAB = new CreativeTabs("avogadro.elements") {

            @Override
            public ItemStack createIcon() {
                var samples = variants(Avogadro.proxy.moleculeItemView(), MoleculeClassification.ELEMENT);
                return samples.isEmpty() ? new ItemStack(CATEGORY_ICON) : samples.getFirst();
            }

            @Override
            public void displayAllRelevantItems(NonNullList<ItemStack> items) {
                if (io.github.wurtzitane.avogadro.Config.PERIODIC_TABLE_CRAFTING.get())
                    items.add(PeriodicTableItem.stack());
                variants(Avogadro.proxy.moleculeItemView(), MoleculeClassification.ELEMENT).forEach(items::add);
            }
        };
    }

    /**
     * Registers model and category icon items with Forge.
     *
     * @param event registry event for items
     */
    @SubscribeEvent
    public static void register(RegistryEvent.Register<Item> event) {
        MODEL.setCreativeTab(TAB);
        event.getRegistry().register(MODEL);
        event.getRegistry().register(CATEGORY_ICON);
    }

    /**
     * Returns the resource location identifier stored on a molecule item stack.
     *
     * @param stack item stack instance
     * @return resource location identifier, or {@code null}
     */
    public static ResourceLocation id(ItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.getItem() != MODEL || !stack.hasTagCompound())
            return null;
        return MoleculeItemMetadata.validatedId(stack.getTagCompound().getString(ID_TAG));
    }

    /**
     * Resolves a molecule definition from an item stack and view.
     *
     * @param stack item stack instance
     * @param view molecule view instance
     * @return resolved {@link MoleculeDef}, or {@code null}
     */
    public static MoleculeDef resolve(ItemStack stack, MoleculeView view) {
        ResourceLocation id = id(stack);
        MoleculeDef definition = id == null ? null : view.get(id);
        return definition;
    }

    /**
     * Creates an item stack corresponding to a given molecule definition.
     *
     * @param definition molecule definition
     * @return constructed item stack instance
     */
    public static ItemStack stack(MoleculeDef definition) {
        if (definition == null || !definition.generateItem())
            throw new IllegalArgumentException("Molecule item is not enabled");
        ItemStack result = new ItemStack(MODEL);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString(ID_TAG, definition.id().toString());
        result.setTagCompound(tag);
        return result;
    }

    /**
     * Returns all sorted item stack variants for a molecule view.
     *
     * @param view molecule view instance
     * @return list of item stacks
     */
    public static List<ItemStack> variants(MoleculeView view) {
        return MoleculeItemOrder.sort(view.generatedItemDefinitions(),
                io.github.wurtzitane.avogadro.Config.CREATIVE_SORT.get(), Avogadro.proxy::moleculeItemName).stream()
                .map(MoleculeItems::stack).toList();
    }

    /**
     * Returns filtered and sorted item stack variants for a specific classification tab.
     *
     * @param view molecule view instance
     * @param tab molecule classification tab
     * @return list of item stacks
     */
    private static List<ItemStack> variants(MoleculeView view, MoleculeClassification tab) {
        return MoleculeItemOrder.sort(
                MoleculeItemOrder.group(view.all(), ELEMENTS_TAB != null, CRYSTALS_TAB != null, POLYMERS_TAB != null,
                        tab),
                (tab == MoleculeClassification.ELEMENT
                        ? io.github.wurtzitane.avogadro.Config.ELEMENT_SORT.get()
                        : io.github.wurtzitane.avogadro.Config.CREATIVE_SORT.get()),
                Avogadro.proxy::moleculeItemName).stream().map(MoleculeItems::stack).toList();
    }

    /**
     * Returns the fallback name string for a molecule definition.
     *
     * @param definition molecule definition
     * @return fallback name string
     */
    public static String fallbackName(MoleculeDef definition) {
        return MoleculeItemMetadata.fallbackName(definition);
    }

    /**
     * Returns the localized display name for a molecule definition on the client side.
     *
     * @param definition molecule definition
     * @return localized display name string
     */
    @SideOnly(Side.CLIENT)
    public static String displayName(MoleculeDef definition) {
        return MoleculeItemMetadata.displayName(definition);
    }

    /** Finite network formulas describe the registered default preview. */
    @SideOnly(Side.CLIENT)
    public static String displayFormula(MoleculeDef definition) {
        String formula = io.github.wurtzitane.avogadro.api.ChemicalFormula.display(definition.formula());
        return definition.inspection().metadata()
                .containsKey(io.github.wurtzitane.avogadro.api.PolymerNetworkDefinition.METADATA_KEY)
                        ? net.minecraft.client.resources.I18n.format("avogadro.network.default_formula", formula)
                        : formula;
    }

    /** Internal item implementation handling subtypes, tooltips, and creative tabs. */
    private static final class ModelItem extends Item {

        /**
         * Returns the item stack display name.
         *
         * @param stack item stack instance
         * @return display name string
         */
        @Override
        public String getItemStackDisplayName(ItemStack stack) {
            MoleculeDef definition = resolve(stack, Avogadro.proxy.moleculeItemView());
            return definition == null
                    ? super.getItemStackDisplayName(stack)
                    : Avogadro.proxy.moleculeItemName(definition);
        }

        /**
         * Populates sub-items for creative inventory tabs.
         *
         * @param tab creative tab
         * @param items non-null item stack list
         */
        @Override
        public void getSubItems(CreativeTabs tab, NonNullList<ItemStack> items) {
            if (tab == CreativeTabs.SEARCH)
                items.addAll(variants(Avogadro.proxy.moleculeItemView()));
            else if (tab == TAB)
                items.addAll(variants(Avogadro.proxy.moleculeItemView(), MoleculeClassification.MOLECULE));
            else if (tab == ELEMENTS_TAB)
                items.addAll(variants(Avogadro.proxy.moleculeItemView(), MoleculeClassification.ELEMENT));
            else if (tab == CRYSTALS_TAB)
                items.addAll(variants(Avogadro.proxy.moleculeItemView(), MoleculeClassification.CRYSTAL));
            else if (tab == POLYMERS_TAB)
                items.addAll(variants(Avogadro.proxy.moleculeItemView(), MoleculeClassification.POLYMER));
        }

        /**
         * Returns all creative tabs associated with this item.
         *
         * @return array of creative tabs
         */
        @Override
        public CreativeTabs[] getCreativeTabs() {
            List<CreativeTabs> tabs = new ArrayList<>();
            tabs.add(TAB);
            if (ELEMENTS_TAB != null)
                tabs.add(ELEMENTS_TAB);
            if (CRYSTALS_TAB != null)
                tabs.add(CRYSTALS_TAB);
            if (POLYMERS_TAB != null)
                tabs.add(POLYMERS_TAB);
            return tabs.toArray(CreativeTabs[]::new);
        }

        /**
         * Adds tooltip information lines to item stacks.
         *
         * 5 * @param stack item stack instance
         *
         * @param world world instance
         * @param lines tooltip lines list
         * @param flag tooltip flag instance
         */
        @SideOnly(Side.CLIENT)
        @Override
        public void addInformation(ItemStack stack, net.minecraft.world.World world, List<String> lines,
                net.minecraft.client.util.ITooltipFlag flag) {
            MoleculeDef definition = resolve(stack, Avogadro.proxy.moleculeItemView());
            if (definition == null) {
                lines.add(net.minecraft.client.resources.I18n.format("avogadro.item.unavailable"));
                ResourceLocation id = id(stack);
                if (id != null)
                    lines.add(id.toString());
                return;
            }
            lines.add(displayFormula(definition));
            var classification = io.github.wurtzitane.avogadro.api.MoleculeClassification.of(definition);
            if (classification == io.github.wurtzitane.avogadro.api.MoleculeClassification.MIXTURE) {
                lines.add(net.minecraft.client.resources.I18n.format("avogadro.item.variable_composition"));
                return;
            }
            Optional<String> result = MoleculeItemMetadata.weightText(definition);
            if (result.isPresent()) {
                lines.add(net.minecraft.client.resources.I18n.format(definition.inspection().metadata()
                        .containsKey(io.github.wurtzitane.avogadro.api.PolymerNetworkDefinition.METADATA_KEY)
                                ? "avogadro.network.default_mass"
                                : classification == io.github.wurtzitane.avogadro.api.MoleculeClassification.POLYMER
                                        ? "avogadro.polymer.repeat_unit_molar_mass"
                                        : classification == io.github.wurtzitane.avogadro.api.MoleculeClassification.CRYSTAL
                                                ? "avogadro.crystal.molar_mass"
                                                : "avogadro.item.entity_mass",
                        result.get()));
            } else
                lines.add(net.minecraft.client.resources.I18n.format("avogadro.item.mass_unavailable"));
        }
    }
}

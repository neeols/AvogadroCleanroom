package io.github.wurtzitane.avogadro.compat.jei;

import io.github.wurtzitane.avogadro.Config;
import io.github.wurtzitane.avogadro.item.MoleculeItems;
import io.github.wurtzitane.avogadro.molecule.MoleculeDef;
import io.github.wurtzitane.avogadro.molecule.MoleculeStore;
import mezz.jei.api.ICollapsibleGroupRegistry;
import mezz.jei.api.IJeiRuntime;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.IModRegistry;
import mezz.jei.api.IRecipeRegistry;
import mezz.jei.api.JEIPlugin;
import mezz.jei.api.recipe.IRecipeCategoryRegistration;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Loaded only by the optional client recipe browser, never by Avogadro's common entry point. */
@JEIPlugin
public final class AvogadroJeiPlugin implements IModPlugin {

    /** Mapped entries of registered molecule categories. */
    private final Map<ResourceLocation, MoleculeCategory.Entry> entries = new LinkedHashMap<>();

    /** JEI runtime instance. */
    private IJeiRuntime runtime;

    /** Revision hash tracker for molecule store changes. */
    private long revision = Long.MIN_VALUE;

    /** Flag indicating whether JEI category integration is enabled. */
    private boolean enabled;

    /** Flag indicating whether event bus listening is active. */
    private boolean listening;

    /** JEI ingredient registry reference. */
    private mezz.jei.api.ingredients.IIngredientRegistry ingredients;

    /** Mapped definitions of ingredient items. */
    private final Map<ResourceLocation, MoleculeDef> ingredientDefinitions = new LinkedHashMap<>();

    /** Mapped item stacks for ingredients. */
    private final Map<ResourceLocation, ItemStack> ingredientStacks = new LinkedHashMap<>();

    /** Flag indicating whether ingredients have been initialized. */
    private boolean initializedIngredients;

    /** Collapsible group registry reference. */
    private ICollapsibleGroupRegistry collapsibleGroups;

    /** Mapped grouped identifier sets. */
    private final Map<String, Set<ResourceLocation>> groupedIds = new LinkedHashMap<>();

    /**
     * Registers item subtypes for molecule item stacks.
     *
     * @param registry subtype registry
     */
    @Override public void registerItemSubtypes(mezz.jei.api.ISubtypeRegistry registry) {
        registry.registerSubtypeInterpreter(io.github.wurtzitane.avogadro.item.MoleculeItems.MODEL, stack -> {
            ResourceLocation id = io.github.wurtzitane.avogadro.item.MoleculeItems.id(stack);
            return id == null ? "unavailable" : id.toString();
        });
    }

    /**
     * Registers recipe categories for Avogadro.
     *
     * @param registry recipe category registration instance
     */
    @Override
    public void registerCategories(IRecipeCategoryRegistration registry) {
        // Register the category even when hidden so a config reload can enable it without restarting HEI.
        registry.addRecipeCategories(new MoleculeCategory(registry.getJeiHelpers().getGuiHelper()));
    }

    /**
     * Registers mod plugins, entries, and recipes with JEI.
     *
     * @param registry mod registry instance
     */
    @Override
    public void register(IModRegistry registry) {
        ingredients = registry.getIngredientRegistry();
        initializedIngredients = false;
        ingredientDefinitions.clear();
        ingredientStacks.clear();
        entries.clear();
        for (MoleculeCategory.Entry entry : MoleculeCategory.entries()) entries.put(entry.id(), entry);
        registry.addRecipes(entries.values(), MoleculeCategory.UID);
        revision = MoleculeStore.revision();
        enabled = Config.JEI_CATEGORY.get();
    }

    /**
     * Registers collapsible item groups with JEI.
     *
     * @param registry collapsible group registry
     */
    @Override
    public void registerCollapsibleGroups(ICollapsibleGroupRegistry registry) {
        collapsibleGroups = registry;
        groupedIds.clear();
        refreshGroups();
    }

    /** Refreshes all collapsible item groups. */
    private void refreshGroups() {
        if (collapsibleGroups == null) return;
        refreshGroup("molecules", MoleculeItems.TAB);
        refreshGroup("elements", MoleculeItems.ELEMENTS_TAB);
        refreshGroup("crystals", MoleculeItems.CRYSTALS_TAB);
        refreshGroup("polymers", MoleculeItems.POLYMERS_TAB);
    }

    /**
     * Refreshes a specific collapsible item group by name and creative tab.
     *
     * @param name group name
     * @param tab  creative tab
     */
    private void refreshGroup(String name, CreativeTabs tab) {
        if (tab == null) return;
        NonNullList<ItemStack> stacks = NonNullList.create();
        MoleculeItems.MODEL.getSubItems(tab, stacks);
        Set<ResourceLocation> ids = stacks.stream().map(MoleculeItems::id).collect(Collectors.toSet());
        if (ids.equals(groupedIds.get(name))) return;
        collapsibleGroups.newGroup("avogadro:" + name, "itemGroup.avogadro." + name)
                .add(stacks.toArray()).build();
        groupedIds.put(name, ids);
    }

    /**
     * Callback invoked when the JEI runtime becomes available.
     *
     * @runtime JEI runtime instance
     */
    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) {
        this.runtime = runtime;
        refreshItems();
        applyVisibility();
        if (!listening) {
            listening = true;
            MinecraftForge.EVENT_BUS.register(this);
        }
    }

    /**
     * Handles client tick events to sync config changes and store revisions.
     *
     * @param event client tick event
     */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || runtime == null) return;
        if (enabled != Config.JEI_CATEGORY.get()) {
            enabled = Config.JEI_CATEGORY.get();
            applyVisibility();
        }
        long currentRevision = MoleculeStore.revision();
        if (revision != currentRevision) {
            refreshRecipes();
            refreshItems();
            revision = currentRevision;
        }
    }

    /**
     * Handles keyboard input events for pausing or examining molecules in JEI.
     *
     * @param event keyboard input event
     */
    @SubscribeEvent(priority = net.minecraftforge.fml.common.eventhandler.EventPriority.HIGH)
    public void onKeyboard(net.minecraftforge.client.event.GuiScreenEvent.KeyboardInputEvent.Pre event) {
        if (!org.lwjgl.input.Keyboard.getEventKeyState() || org.lwjgl.input.Keyboard.isRepeatEvent()) return;
        if (handleExamineKey(runtime, event.getGui(), mezz.jei.input.MouseHelper.getX(), mezz.jei.input.MouseHelper.getY(),
                org.lwjgl.input.Keyboard.getEventKey())) { event.setCanceled(true); return; }
        if (handlePauseKey(runtime, event.getGui(), mezz.jei.input.MouseHelper.getX(), mezz.jei.input.MouseHelper.getY(),
                org.lwjgl.input.Keyboard.getEventKey())) event.setCanceled(true);
    }

    /** HEI has no wrapper keyboard callback; route only this key through its native hovered layout. */
    public static boolean handlePauseKey(IJeiRuntime runtime, net.minecraft.client.gui.GuiScreen gui, int mouseX, int mouseY, int key) {
        if (!Config.JEI_CATEGORY.get() || runtime == null || gui != runtime.getRecipesGui()
                || !(gui instanceof mezz.jei.gui.recipes.RecipesGui recipes)
                || recipes.hasKeyboardFocus() || runtime.getIngredientListOverlay().hasKeyboardFocus()
                || !io.github.wurtzitane.avogadro.client.AvogadroKeys.PAUSE_MOLECULE.isActiveAndMatches(key)) return false;
        var layout = recipes.getRecipeLayout(mouseX, mouseY);
        var entry = layout == null ? null : MoleculeCategory.entryFor(layout);
        return entry != null && entry.togglePause(mouseX - layout.getPosX(), mouseY - layout.getPosY());
    }

    /**
     * Handles keypresses for opening the molecule examiner window from JEI recipe layouts.
     *
     * @param runtime JEI runtime
     * @param gui     current GUI screen
     * @param mouseX  mouse X coordinate
     * @param mouseY  mouse Y coordinate
     * @param key     keyboard key code
     * @return {@code true} if handled and canceled, {@code false} otherwise
     */
    public static boolean handleExamineKey(IJeiRuntime runtime, net.minecraft.client.gui.GuiScreen gui, int mouseX, int mouseY, int key) {
        if (!Config.JEI_CATEGORY.get() || runtime == null || gui != runtime.getRecipesGui()
                || !(gui instanceof mezz.jei.gui.recipes.RecipesGui recipes)
                || recipes.hasKeyboardFocus() || runtime.getIngredientListOverlay().hasKeyboardFocus()
                || !io.github.wurtzitane.avogadro.client.AvogadroKeys.EXAMINE_MOLECULE.isActiveAndMatches(key)) return false;
        var layout = recipes.getRecipeLayout(mouseX, mouseY);
        var entry = layout == null ? null : MoleculeCategory.entryFor(layout);
        if (entry == null || !MoleculeCategory.Entry.insideViewport(mouseX - layout.getPosX(), mouseY - layout.getPosY())) return false;
        io.github.wurtzitane.avogadro.client.MoleculeExaminer.open(entry.definition());
        return true;
    }

    /** Applies recipe category visibility based on configuration settings. */
    private void applyVisibility() {
        if (enabled) runtime.getRecipeRegistry().unhideRecipeCategory(MoleculeCategory.UID);
        else runtime.getRecipeRegistry().hideRecipeCategory(MoleculeCategory.UID);
    }

    /** Refreshes item ingredients in the JEI registry. */
    private void refreshItems() {
        var type = mezz.jei.api.ingredients.VanillaTypes.ITEM;
        List<ItemStack> removed = new ArrayList<>();
        if (!initializedIngredients) {
            // Reconcile the creative-tab variants already indexed by HEI once. Later revisions
            // operate only on our owned IDs, without rescanning every ingredient in the pack.
            for (ItemStack stack : ingredients.getAllIngredients(type)) {
                if (stack.getItem() != MoleculeItems.MODEL) continue;
                ResourceLocation id = MoleculeItems.id(stack);
                if (id == null) removed.add(stack);
                else ingredientStacks.put(id, stack.copy());
            }
        }
        Map<ResourceLocation, MoleculeDef> updated = new LinkedHashMap<>();
        List<ItemStack> added = new ArrayList<>();
        for (MoleculeDef definition : MoleculeStore.view().generatedItemDefinitions()) {
            updated.put(definition.id(), definition);
            MoleculeDef old = ingredientDefinitions.get(definition.id());
            if (old != null && equivalent(old, definition)) continue;
            ItemStack previous = ingredientStacks.get(definition.id());
            if (previous != null) removed.add(previous);
            added.add(MoleculeItems.stack(definition));
        }
        ingredientStacks.forEach((id, stack) -> { if (!updated.containsKey(id)) removed.add(stack); });
        if (!removed.isEmpty() || !added.isEmpty()) refreshGroups();
        // Removing the old UID first also refreshes search metadata when only name/formula
        // changes. Recipe and usage indexes are refreshed independently by refreshRecipes().
        if (!removed.isEmpty()) ingredients.removeIngredientsAtRuntime(type, removed);
        if (!added.isEmpty()) ingredients.addIngredientsAtRuntime(type, added);
        ingredientStacks.keySet().retainAll(updated.keySet());
        for (ItemStack stack : added) ingredientStacks.put(MoleculeItems.id(stack), stack.copy());
        ingredientDefinitions.clear();
        ingredientDefinitions.putAll(updated);
        initializedIngredients = true;
    }

    @SuppressWarnings("deprecation")
    private void refreshRecipes() {
        IRecipeRegistry registry = runtime.getRecipeRegistry();
        Map<ResourceLocation, MoleculeCategory.Entry> updated = new LinkedHashMap<>();
        for (MoleculeDef definition : MoleculeCategory.sortedDefinitions()) {
            MoleculeCategory.Entry old = entries.get(definition.id());
            if (old != null && equivalent(old.definition(), definition)) {
                updated.put(old.id(), old);
            } else {
                if (old != null) registry.hideRecipe(old, MoleculeCategory.UID);
                // Resolve associated ItemStacks only when the displayed recipe actually changes.
                MoleculeCategory.Entry candidate = new MoleculeCategory.Entry(definition);
                // The 1.12 API is deprecated for vanilla crafting changes, but remains the public
                // mechanism for adding these display-only wrappers. It rebuilds the usage index.
                registry.addRecipe(candidate, MoleculeCategory.UID);
                updated.put(candidate.id(), candidate);
            }
        }
        for (MoleculeCategory.Entry old : entries.values()) {
            if (!updated.containsKey(old.id())) registry.hideRecipe(old, MoleculeCategory.UID);
        }
        entries.clear();
        entries.putAll(updated);
    }

    /**
     * Compares two molecule definitions for semantic equivalence.
     *
     * @param left  left molecule definition
     * @param right right molecule definition
     * @return {@code true} if equivalent, {@code false} otherwise
     */
    private static boolean equivalent(MoleculeDef left, MoleculeDef right) {
        if (left == right) return true;
        return left.id().equals(right.id()) && left.formula().equals(right.formula())
                && left.generateItem() == right.generateItem() && Objects.equals(left.name(), right.name())
                && left.items().equals(right.items()) && left.fluids().equals(right.fluids())
                && left.itemTags().equals(right.itemTags()) && left.fluidTags().equals(right.fluidTags())
                && left.inspection().equals(right.inspection())
                && (left.structure() == right.structure()
                || left.structure().atoms().equals(right.structure().atoms())
                && left.structure().bonds().equals(right.structure().bonds()));
    }
}
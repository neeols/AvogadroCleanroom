package io.github.wurtzitane.avogadro.compat.jei;

import io.github.wurtzitane.avogadro.client.PixelFont;

import io.github.wurtzitane.avogadro.client.MoleculeRenderer;
import io.github.wurtzitane.avogadro.client.MoleculeCamera;
import io.github.wurtzitane.avogadro.client.MoleculeCategoryStyle;
import io.github.wurtzitane.avogadro.client.MoleculeViewport;
import io.github.wurtzitane.avogadro.client.MoleculeText;
import io.github.wurtzitane.avogadro.client.ExaminerPresentation;
import io.github.wurtzitane.avogadro.Config;
import io.github.wurtzitane.avogadro.molecule.MoleculeDef;
import io.github.wurtzitane.avogadro.molecule.MoleculeStore;
import mezz.jei.api.IGuiHelper;
import mezz.jei.api.gui.IDrawable;
import mezz.jei.api.gui.IRecipeLayout;
import mezz.jei.api.ingredients.IIngredients;
import mezz.jei.api.ingredients.VanillaTypes;
import mezz.jei.api.recipe.IRecipeCategory;
import mezz.jei.api.recipe.IRecipeWrapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A compact molecule card; only its model viewport consumes drag input. */
public final class MoleculeCategory implements IRecipeCategory<MoleculeCategory.Entry> {

    /** X coordinate position for the help button icon. */
    private static final int HELP_X = 166;

    /** Y coordinate position for the help button icon. */
    private static final int HELP_Y = 6;

    /** Size dimension in pixels for the help button. */
    private static final int HELP_SIZE = 10;

    /** Recipe category unique identifier string. */
    public static final String UID = "avogadro:molecules";

    /** Resource location for the category icon texture. */
    public static final ResourceLocation ICON = new ResourceLocation("avogadro", "textures/gui/molecules_icon.png");

    /** Background drawable for recipe cards. */
    private final IDrawable background;

    /** Category tab icon drawable. */
    private final IDrawable icon;

    /** Slot background drawable. */
    private final IDrawable slot;

    /** Revision tracker for instruction strings. */
    private static long instructionsRevision = Long.MIN_VALUE;

    /** Cached drag instruction lines. */
    private static List<String> dragInstructions = List.of();

    /** Cached prerendered instruction lines. */
    private static List<String> prerenderedInstructions = List.of();

    /** Cached paused drag instruction lines. */
    private static List<String> pausedDragInstructions = List.of();

    /** Cached paused prerendered instruction lines. */
    private static List<String> pausedPrerenderedInstructions = List.of();

    /** Cached instruction key code. */
    private static int instructionsKey = Integer.MIN_VALUE;

    /** Cached instruction key modifier. */
    private static net.minecraftforge.client.settings.KeyModifier instructionsModifier;

    /** Weak map associating recipe layouts with entries. */
    private static final java.util.Map<IRecipeLayout, Entry> layouts = new java.util.WeakHashMap<>();

    /**
     * Retrieves the recipe entry associated with a given layout.
     *
     * @param layout recipe layout
     * @return matching entry instance, or {@code null}
     */
    static Entry entryFor(IRecipeLayout layout) { return layouts.get(layout); }

    /** Refreshes cached instruction tooltip strings when configuration or keybindings change. */
    private static void refreshInstructions() {
        long revision = MoleculeCategoryStyle.reloadRevision();
        var binding = io.github.wurtzitane.avogadro.client.AvogadroKeys.PAUSE_MOLECULE;
        if (instructionsRevision == revision && instructionsKey == binding.getKeyCode()
                && instructionsModifier == binding.getKeyModifier()) return;
        String pause = I18n.format("avogadro.molecules.pause", binding.getDisplayName());
        String resume = I18n.format("avogadro.molecules.resume", binding.getDisplayName());
        List<String> controls = List.of(I18n.format("avogadro.molecules.drag"), I18n.format("avogadro.molecules.pan"),
                I18n.format("avogadro.molecules.zoom"), I18n.format("avogadro.molecules.reset"));
        var lines = new ArrayList<>(controls);
        lines.add(I18n.format("avogadro.molecules.examine", io.github.wurtzitane.avogadro.client.AvogadroKeys.EXAMINE_MOLECULE.getDisplayName()));
        lines.add(pause);
        dragInstructions = List.copyOf(lines);
        lines.set(lines.size() - 1, resume);
        pausedDragInstructions = List.copyOf(lines);
        prerenderedInstructions = List.of(I18n.format("avogadro.molecules.prerendered"), pause);
        pausedPrerenderedInstructions = List.of(I18n.format("avogadro.molecules.prerendered"), resume);
        instructionsKey = binding.getKeyCode();
        instructionsModifier = binding.getKeyModifier();
        instructionsRevision = revision;
    }

    /**
     * Constructs a new molecule recipe category.
     *
     * @param helper GUI helper instance for creating drawables
     */
    public MoleculeCategory(IGuiHelper helper) {
        background = helper.createDrawable(MoleculeCategoryStyle.TEXTURE, 0, 0, 180, 96, 180, 96);
        // A complete texture mapped onto a 16 px tab icon; no molecule definition is registered.
        icon = helper.createDrawable(ICON, 0, 0, 16, 16, 16, 16);
        slot = helper.getSlotDrawable();
    }

    /**
     * Retrieves the category unique identifier.
     *
     * @return UID string
     */
    @Override public String getUid() { return UID; }

    /**
     * Retrieves the localized category title string.
     *
     * @return title string
     */
    @Override public String getTitle() { return I18n.format("jei.avogadro.molecules"); }

    /**
     * Retrieves the mod name associated with this category.
     *
     * @return mod name string
     */
    @Override public String getModName() { return "Avogadro"; }

    /**
     * Retrieves the category background drawable.
     *
     * @return background drawable instance
     */
    @Override public IDrawable getBackground() { return background; }

    /**
     * Retrieves the category tab icon drawable.
     *
     * @return icon drawable instance
     */
    @Override public IDrawable getIcon() { return icon; }

    /**
     * Populates recipe layout slots and ingredients for a given recipe entry.
     *
     * @param layout      recipe layout instance
     * @param entry       recipe entry
     * @param ingredients JEI ingredients container
     */
    @Override
    public void setRecipe(IRecipeLayout layout, Entry entry, IIngredients ingredients) {
        layouts.put(layout, entry);
        if (!entry.inputs().isEmpty()) {
            layout.getItemStacks().init(0, true, 4, 4);
            layout.getItemStacks().setBackground(0, slot);
            layout.getItemStacks().set(0, entry.inputs());
        }
    }

    /**
     * Retrieves a list of all recipe entries for sorted molecule definitions.
     *
     * @return list of recipe entry instances
     */
    public static List<Entry> entries() {
        List<Entry> entries = new ArrayList<>();
        for (MoleculeDef definition : sortedDefinitions()) entries.add(new Entry(definition));
        return entries;
    }

    /**
     * Retrieves all sorted molecule definitions from the store.
     *
     * @return list of molecule definitions
     */
    static List<MoleculeDef> sortedDefinitions() {
        return MoleculeStore.view().sortedDefinitions();
    }

    /** Recipe wrapper representing an individual molecule display entry card. */
    public static final class Entry implements IRecipeWrapper {
        private final MoleculeDef definition;
        private final List<ItemStack> inputs;
        private final List<net.minecraftforge.fluids.FluidStack> fluids;
        private final String nameKey;
        private final String fallbackName;
        private final int sphereSize;
        private final int cubeSize;
        private final MoleculeCamera camera = new MoleculeCamera(96, 88);
        private Config.RenderingMode cameraMode = Config.RENDERING_MODE.get();
        private Labels labels;
        private long nameRevision = Long.MIN_VALUE;
        private String cachedName;

        /**
         * Constructs a new recipe entry for a molecule definition.
         *
         * @param definition molecule definition
         */
        public Entry(MoleculeDef definition) {
            this.definition = definition;
            nameKey = "molecule." + id().getNamespace() + "." + id().getPath().replace('/', '.');
            fallbackName = readableName(id().getPath());
            sphereSize = MoleculeViewport.fitSize(definition.structure(), 72, 96, 88, false);
            cubeSize = MoleculeViewport.fitSize(definition.structure(), 72, 96, 88, true);
            List<ItemStack> items = new ArrayList<>();
            if (definition.generateItem()) items.add(io.github.wurtzitane.avogadro.item.MoleculeItems.stack(definition));
            items.addAll(MoleculeStore.associatedItems(definition));
            inputs = List.copyOf(items);
            fluids = MoleculeStore.associatedFluids(definition);
        }

        /**
         * Retrieves the molecule identifier.
         *
         * @return resource location identifier
         */
        public ResourceLocation id() { return definition.id(); }

        /**
         * Retrieves the molecule definition.
         *
         * @return molecule definition instance
         */
        public MoleculeDef definition() { return definition; }

        /**
         * Retrieves the input item stacks.
         *
         * @return list of item stacks
         */
        public List<ItemStack> inputs() { return inputs; }

        /**
         * Populates JEI ingredients with associated item and fluid lists.
         *
         * @param ingredients JEI ingredients container
         */
        @Override
        public void getIngredients(IIngredients ingredients) {
            if (!inputs.isEmpty()) {
                List<List<ItemStack>> associations = List.of(inputs);
                ingredients.setInputLists(VanillaTypes.ITEM, associations);
                ingredients.setOutputLists(VanillaTypes.ITEM, associations);
            }
            if (!fluids.isEmpty()) {
                ingredients.setInputLists(VanillaTypes.FLUID, List.of(fluids));
                ingredients.setOutputLists(VanillaTypes.FLUID, List.of(fluids));
            }
        }

        /**
         * Renders the 3D molecule viewport, name labels, formulas, and help icon.
         *
         * @param minecraft Minecraft client instance
         * @param width     card width
         * @param height    card height
         * @param mouseX    mouse X coordinate
         * @param mouseY    mouse Y coordinate
         */
        @Override
        public void drawInfo(Minecraft minecraft, int width, int height, int mouseX, int mouseY) {
            syncCameraMode();
            camera.rendered();
            long now = System.nanoTime();
            float automaticYaw = MoleculeRenderer.rotationAngle();
            if (camera.isDragging()) {
                int button = camera.draggingButton();
                if (Mouse.isButtonDown(button) && Display.isActive()) {
                    camera.drag(mouseX, mouseY, button);
                } else {
                    camera.release(button, automaticYaw, now);
                }
            }
            MoleculeRenderer.renderInViewport(definition.structure(), 80, 4, 96, 88,
                    Config.CUBIC_ATOMS.get() ? cubeSize : sphereSize,
                    camera.yaw(automaticYaw, now), camera.pitch(now), camera.panX(), camera.panY(), camera.zoom());
            MoleculeCategoryStyle.Palette colors = MoleculeCategoryStyle.palette();
            FontRenderer font = PixelFont.wrap(minecraft.fontRenderer);
            refreshLabels(font);
            for (PositionedLabel label : labels.lines()) {
                label.line().draw(font, 4, label.top(), label.scale(), label.formula() ? colors.secondary() : colors.primary());
            }
            GL11.glPushMatrix();
            try {
                GL11.glTranslatef(HELP_X, HELP_Y, 0);
                GL11.glScalef(HELP_SIZE / 14f, HELP_SIZE / 14f, 1);
                ExaminerPresentation.drawInfoIcon(0, 0, insideHelp(mouseX, mouseY), colors.secondary());
            } finally {
                GL11.glPopMatrix();
            }
        }

        /**
         * Retrieves tooltip strings when hovering over labels or the help icon.
         *
         * @param mouseX mouse X coordinate
         * @param mouseY mouse Y coordinate
         * @return list of tooltip string lines
         */
        @Override
        public List<String> getTooltipStrings(int mouseX, int mouseY) {
            if (camera.isDragging()) return List.of();
            refreshLabels(PixelFont.renderer());
            if (mouseX >= 4 && mouseX < 74 && mouseY >= 30 && mouseY < labels.nameBottom())
                return labels.fullName();
            if (insideHelp(mouseX, mouseY)) {
                refreshInstructions();
                List<String> lines = Config.RENDERING_MODE.get() == Config.RenderingMode.PRERENDERED
                        ? (camera.rotation().isPaused() ? pausedPrerenderedInstructions : prerenderedInstructions)
                        : (camera.rotation().isPaused() ? pausedDragInstructions : dragInstructions);
                return camera.isDragging() ? List.of() : lines;
            }
            return List.of();
        }

        /**
         * Toggles animation pause state when clicking inside the viewport.
         *
         * @param mouseX mouse X coordinate
         * @param mouseY mouse Y coordinate
         * @return {@code true} if toggled successfully, {@code false} otherwise
         */
        public boolean togglePause(int mouseX, int mouseY) {
            if (!insideViewport(mouseX, mouseY)) return false;
            syncCameraMode();
            camera.rendered();
            camera.rotation().togglePaused(MoleculeRenderer.rotationAngle(), System.nanoTime());
            return true;
        }

        /** Synchronizes camera mode with current configuration settings. */
        private void syncCameraMode() {
            if (cameraMode != Config.RENDERING_MODE.get()) {
                camera.reset();
                cameraMode = Config.RENDERING_MODE.get();
            }
        }

        /**
         * Handles mouse click events within the recipe card view.
         *
         * @param minecraft Minecraft client instance
         * @param mouseX    mouse X coordinate
         * @param mouseY    mouse Y coordinate
         * @param button    mouse button index
         * @return {@code true} if handled, {@code false} otherwise
         */
        @Override
        public boolean handleClick(Minecraft minecraft, int mouseX, int mouseY, int button) {
            if (Config.RENDERING_MODE.get() == Config.RenderingMode.PRERENDERED) return insideViewport(mouseX, mouseY);
            if (!insideViewport(mouseX, mouseY)) return false;
            if (button == 2) {
                camera.reset();
                return true;
            }
            return camera.start(mouseX, mouseY, button, MoleculeRenderer.rotationAngle(), System.nanoTime());
        }

        /**
         * Handles mouse drag events for rotating the camera.
         *
         * @param mouseX  mouse X coordinate
         * @param mouseY  mouse Y coordinate
         * @param button  mouse button index
         * @param elapsed elapsed time
         * @return {@code true} if handled, {@code false} otherwise
         */
        @Override
        public boolean handleMouseDrag(int mouseX, int mouseY, int button, long elapsed) {
            if (Config.RENDERING_MODE.get() == Config.RenderingMode.PRERENDERED) return false;
            return camera.drag(mouseX, mouseY, button);
        }

        /**
         * Handles mouse release events.
         *
         * @param mouseX mouse X coordinate
         * @param mouseY mouse Y coordinate
         * @param button mouse button index
         * @return {@code true} if handled, {@code false} otherwise
         */
        @Override
        public boolean handleMouseReleased(int mouseX, int mouseY, int button) {
            if (Config.RENDERING_MODE.get() == Config.RenderingMode.PRERENDERED) return false;
            // HEI 4.36.1 forwards absolute release coordinates, unlike click/drag.
            return camera.release(button, MoleculeRenderer.rotationAngle(), System.nanoTime());
        }

        /**
         * Handles mouse scroll events for zooming.
         *
         * @param mouseX mouse X coordinate
         * @param mouseY mouse Y coordinate
         * @param delta  scroll wheel delta amount
         * @return {@code true} if handled, {@code false} otherwise
         */
        @Override
        public boolean handleMouseScroll(int mouseX, int mouseY, int delta) {
            if (Config.RENDERING_MODE.get() == Config.RenderingMode.PRERENDERED) return insideViewport(mouseX, mouseY);
            return insideViewport(mouseX, mouseY) && camera.scroll(delta, mouseX - 128, mouseY - 48);
        }

        /**
         * Retrieves the cached display name for the molecule definition.
         *
         * @return display name string
         */
        private String displayName() {
            long revision = MoleculeCategoryStyle.reloadRevision();
            if (nameRevision != revision) {
                cachedName = io.github.wurtzitane.avogadro.item.MoleculeItems.displayName(definition);
                nameRevision = revision;
            }
            return cachedName;
        }

        /**
         * Refreshes text label measurements if scaling or resources have changed.
         *
         * @param font font renderer instance
         */
        private void refreshLabels(FontRenderer font) {
            double scale = Config.TEXT_SCALE.get();
            long revision = MoleculeCategoryStyle.reloadRevision();
            if (labels == null || !labels.matches(font, scale, revision))
                labels = measureLabels(font, scale, revision);
        }

        /**
         * Measures and constructs positioning data for name and formula labels.
         *
         * @param font       font renderer instance
         * @param configured configured text scale factor
         * @param revision   resource revision hash
         * @return constructed {@link Labels} instance
         */
        private Labels measureLabels(FontRenderer font, double configured, long revision) {
            String text = displayName();
            float requested = (float) configured;
            float single = Math.min(requested, Math.min(70f / Math.max(1, font.getStringWidth(text)), 24f / font.FONT_HEIGHT));
            float wrapped = Math.min(requested, 24f / (font.FONT_HEIGHT * 2 + 2));
            float scale = Math.max(single, wrapped);
            List<String> lines = single >= wrapped ? List.of(text) : MoleculeText.wrap(text, 70 / scale, font::getStringWidth);
            List<PositionedLabel> measured = new ArrayList<>(lines.size() + 1);
            for (int index = 0; index < lines.size(); index++) {
                measured.add(new PositionedLabel(MoleculeText.plain(lines.get(index), font::getStringWidth, font.FONT_HEIGHT),
                        30 + index * (font.FONT_HEIGHT + 2) * scale, scale, false));
            }
            MoleculeText.Line formula = MoleculeText.formula(io.github.wurtzitane.avogadro.item.MoleculeItems.displayFormula(definition), font::getStringWidth, font.FONT_HEIGHT);
            float formulaScale = Math.min(requested,
                    Math.min(70f / Math.max(1f, formula.width()), 27f / Math.max(1f, formula.height())));
            measured.add(new PositionedLabel(formula, 59, formulaScale, true));
            boolean clipped = !String.join("", lines).replaceAll("\\s+", "")
                    .equals(text.replaceAll("\\s+", ""));
            float nameBottom = 30 + (lines.size() * (font.FONT_HEIGHT + 2) - 2) * scale;
            return new Labels(font, configured, revision, font.FONT_HEIGHT, font.getUnicodeFlag(), font.getBidiFlag(),
                    List.copyOf(measured), nameBottom, clipped ? List.of(text) : List.of());
        }

        /**
         * Record representing a positioned text line label.
         *
         * @textLabel line text line
         * @param top     top Y coordinate
         * @param scale   scale factor
         * @param formula whether label is a chemical formula
         */
        private record PositionedLabel(MoleculeText.Line line, float top, float scale, boolean formula) {}

        /**
         * Record holding layout measurements and cache parameters for rendered labels.
         *
         * @param font       font renderer
         * @param scale      text scale factor
         * @param revision   resource revision hash
         * @param fontHeight font height in pixels
         * @param unicode    whether unicode flag is active
         * @param bidi       whether bidirectional flag is active
         * @param lines      list of positioned label lines
         * @param nameBottom bottom Y coordinate of name section
         * @param fullName   full name string list for tooltips
         */
        private record Labels(FontRenderer font, double scale, long revision, int fontHeight,
                              boolean unicode, boolean bidi, List<PositionedLabel> lines, float nameBottom, List<String> fullName) {

            /**
             * Checks whether cached labels match current font and configuration parameters.
             *
             * @param current    current font renderer
             * @param configured configured text scale
             * @param resources  resource revision hash
             * @return {@code true} if matching, {@code false} otherwise
             */
            boolean matches(FontRenderer current, double configured, long resources) {
                return font == current && scale == configured && revision == resources && fontHeight == current.FONT_HEIGHT
                        && unicode == current.getUnicodeFlag() && bidi == current.getBidiFlag();
            }
        }

        /**
         * Checks whether coordinates fall inside the 3D model viewport.
         *
         * @param x mouse X coordinate
         * @param y mouse Y coordinate
         * @return {@code true} if inside viewport, {@code false} otherwise
         */
        public static boolean insideViewport(int x, int y) {
            return x >= 80 && x < 176 && y >= 4 && y < 92 && !insideHelp(x, y);
        }

        /**
         * Checks whether coordinates fall inside the help icon button bounds.
         *
         * @param x mouse X coordinate
         * @param y mouse Y coordinate
         * @return {@code true} if inside help button, {@code false} otherwise
         */
        private static boolean insideHelp(int x, int y) {
            return x >= HELP_X && x < HELP_X + HELP_SIZE && y >= HELP_Y && y < HELP_Y + HELP_SIZE;
        }
    }

    /**
     * Converts a raw resource path string into a readable title case name.
     *
     * @param path resource path string
     * @return formatted readable name string
     */
    static String readableName(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1).replace('_', ' ').replace('-', ' ');
        StringBuilder result = new StringBuilder();
        for (String word : name.split(" +")) {
            if (word.isEmpty()) continue;
            if (!result.isEmpty()) result.append(' ');
            result.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
        }
        return result.isEmpty() ? path : result.toString();
    }
}
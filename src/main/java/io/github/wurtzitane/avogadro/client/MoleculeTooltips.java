package io.github.wurtzitane.avogadro.client;

import io.github.wurtzitane.avogadro.Config;
import io.github.wurtzitane.avogadro.molecule.MoleculeDef;
import io.github.wurtzitane.avogadro.molecule.MoleculeStore;

import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.RenderTooltipEvent;
import net.minecraftforge.fluids.FluidStack;

import org.lwjgl.opengl.GL11;

/** Extra image space shared by the native Forge, HEI and CodeChickenLib tooltip renderers. */
public final class MoleculeTooltips {

    /** Spacing gap size in pixels between content elements. */
    private static final int GAP = 4;

    /** Horizontal padding total in pixels for tooltip containers. */
    private static final int HORIZONTAL_PADDING = 24;

    /** Vertical padding total in pixels for tooltip containers. */
    private static final int VERTICAL_PADDING = 12;

    /** Thread-local context frame for active tooltip rendering. */
    private static final ThreadLocal<Frame> FRAME = new ThreadLocal<>();

    /** Thread-local ingredient container for contextual lookups. */
    private static final ThreadLocal<Ingredient> INGREDIENT = new ThreadLocal<>();

    private MoleculeTooltips() {}

    /**
     * Public fluid context for one tooltip draw; nested tooltips do not inherit it.
     *
     * @param fluid fluid stack ingredient context
     * @param draw runnable task performing the draw
     */
    public static void withFluidTooltip(FluidStack fluid, Runnable draw) {
        withIngredient(fluid, draw);
    }

    /**
     * Sets an ingredient context for the duration of the supplied draw task execution.
     *
     * @param ingredient ingredient object (item stack or fluid stack)
     * @param draw runnable task performing the draw
     */
    public static void withIngredient(Object ingredient, Runnable draw) {
        Ingredient previous = INGREDIENT.get();
        INGREDIENT.set(new Ingredient(ingredient));
        try {
            draw.run();
        } finally {
            if (previous == null)
                INGREDIENT.remove();
            else
                INGREDIENT.set(previous);
        }
    }

    /** Whole-call scope: cancellation, nested tooltips and exceptions restore their caller. */
    public static void withTooltip(Runnable draw) {
        Frame previous = FRAME.get();
        Ingredient source = INGREDIENT.get();
        Object ingredient = source == null || source.claimed ? null : source.value;
        if (source != null)
            source.claimed = true;
        FRAME.set(new Frame(ingredient));
        try {
            draw.run();
        } finally {
            if (previous == null)
                FRAME.remove();
            else
                FRAME.set(previous);
        }
    }

    /**
     * Called after the host has dispatched every Pre listener and honored cancellation.
     *
     * @param event render tooltip pre event
     * @param canceled whether the event was canceled
     */
    public static void afterPre(RenderTooltipEvent.Pre event, boolean canceled) {
        Frame frame = FRAME.get();
        if (frame == null || canceled || !Config.ENABLE_TOOLTIPS.get() || event.getLines().isEmpty())
            return;
        if (!(frame.ingredient instanceof FluidStack) && !event.getStack().isEmpty()
                && io.github.wurtzitane.avogadro.api.AvogadroApi.isItemTooltipExcluded(event.getStack()))
            return;
        ResourceLocation id = frame.ingredient instanceof FluidStack fluid
                ? MoleculeStore.lookup(fluid)
                : MoleculeStore.lookup(event.getStack());
        frame.definition = id == null ? null : MoleculeStore.get(id);
        if (frame.definition == null)
            return;
        frame.screenHeight = event.getScreenHeight();
        int availableWidth = Math.max(0, event.getScreenWidth() - 8);
        if (event.getMaxWidth() > 0)
            availableWidth = Math.min(availableWidth, event.getMaxWidth());
        frame.size = Math.min(Config.TOOLTIP_SIZE.get(), availableWidth - HORIZONTAL_PADDING);
        frame.image = frame.size > 0;
    }

    /**
     * Computes the minimum required width for the tooltip including any molecule preview image.
     *
     * @param nativeWidth original native tooltip width
     * @return adjusted minimum width
     */
    public static int minimumWidth(int nativeWidth) {
        Frame frame = FRAME.get();
        if (frame == null)
            return nativeWidth;
        return Math.max(nativeWidth, frame.image ? frame.size + HORIZONTAL_PADDING : 0);
    }

    /**
     * Checks whether the current tooltip frame contains renderable image content.
     *
     * @return {@code true} if content exists, {@code false} otherwise
     */
    public static boolean hasContent() {
        Frame frame = FRAME.get();
        return frame != null && frame.image;
    }

    /** After native text/item rows are measured, before the host positions its background. */
    public static int reserveHeight(int nativeHeight) {
        Frame frame = FRAME.get();
        if (frame == null || frame.reserved || !hasContent())
            return nativeHeight;
        frame.reserved = true;
        frame.size = Math.min(frame.size, frame.screenHeight - nativeHeight - GAP - VERTICAL_PADDING - 8);
        if (frame.size <= 0) {
            frame.image = false;
            return nativeHeight;
        }
        frame.extraHeight = frame.size + GAP + VERTICAL_PADDING;
        return nativeHeight + frame.extraHeight;
    }

    /** Draw before native PostText listeners, leaving their state and event order alone. */
    public static void beforePostText(RenderTooltipEvent.PostText event) {
        Frame frame = FRAME.get();
        if (frame == null || !hasContent() || !frame.reserved || frame.drawn)
            return;
        frame.drawn = true;
        int x = event.getX() + (event.getWidth() - frame.size) / 2;
        int y = event.getY() + event.getHeight() - frame.extraHeight + GAP + VERTICAL_PADDING / 2;
        draw(frame.definition, x, y, frame.size);
    }

    /**
     * Renders a molecule or crystal definition inside a tooltip window.
     *
     * @param definition molecule definition to render
     * @param x x coordinate
     * @param y y coordinate
     * @param size render size
     */
    public static void draw(MoleculeDef definition, int x, int y, int size) {
        // The renderer changes raw GL only and restores its attribute stack. Keep our
        // translation local instead of wrapping other mods' event listeners.
        int matrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        try {
            GL11.glTranslated(0, 0, 300);
            float yaw = MoleculeRenderer.rotationAngle();
            AtomicPreview.warm(definition);
            if (TooltipAtomControl.active() && AtomicPreview.draw(definition, x, y, size, size, size, yaw, 15,
                    0, 0, 1, x - 12, y - 6, x + size + 12, y + size + 6, System.nanoTime()))
                return;
            if (io.github.wurtzitane.avogadro.api.MoleculeClassification
                    .of(definition) == io.github.wurtzitane.avogadro.api.MoleculeClassification.CRYSTAL)
                CrystalPreview.draw(definition, x, y, size, yaw);
            else if (AtomicPreview.supports(definition))
                MoleculeRenderer.renderLive(definition.structure(), x, y, size, yaw, 15);
            else
                MoleculeRenderer.render(definition.structure(), x, y, size, yaw);
        } finally {
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
            GL11.glMatrixMode(matrixMode);
        }
    }

    /**
     * Holder class representing an ingredient object context.
     */
    private static final class Ingredient {

        private final Object value;
        private boolean claimed;

        /**
         * Creates a ingredient wrapper.
         *
         * @param value ingredient object
         */
        private Ingredient(Object value) {
            this.value = value;
        }
    }

    /**
     * Holder class tracking state for an active tooltip render frame.
     */
    private static final class Frame {

        private final Object ingredient;
        private MoleculeDef definition;
        private int screenHeight;
        private int size;
        private int extraHeight;
        private boolean reserved;
        private boolean drawn;
        private boolean image;

        /**
         * Creates a tooltip render frame.
         *
         * @param ingredient ingredient associated with the tooltip
         */
        private Frame(Object ingredient) {
            this.ingredient = ingredient;
        }
    }
}

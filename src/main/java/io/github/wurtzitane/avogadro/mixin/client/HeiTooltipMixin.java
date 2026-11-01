package io.github.wurtzitane.avogadro.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;
import io.github.wurtzitane.avogadro.client.MoleculeTooltips;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.event.RenderTooltipEvent;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.EventBus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.awt.Rectangle;
import java.util.List;
import java.util.function.Consumer;

/** Mixin targeting JEI's TooltipRenderer class to inject custom molecule tooltip scoping, event handling, and layout sizing for rich text and items. */
@Pseudo
@Mixin(targets = "mezz.jei.gui.TooltipRenderer", remap = false)
public abstract class HeiTooltipMixin {

    /** Target method signature string for JEI hovering text and items drawing. */
    private static final String DRAW = "drawHoveringTextAndItems(Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/Minecraft;Ljava/util/List;Ljava/util/List;IIILnet/minecraft/client/gui/FontRenderer;Ljava/util/function/Consumer;)V";

    /**
     * Wraps the drawing method to execute within a scoped molecule tooltip context.
     *
     * @param stack     item stack being rendered
     * @param minecraft Minecraft client instance
     * @param lines     tooltip text lines list
     * @param itemLines nested item lines list
     * @param x         render X coordinate
     * @param y         render Y coordinate
     * @param maxWidth  maximum allowed width
     * @param font      font renderer instance
     * @param layout    layout rectangle consumer
     * @param original  original operation invocation
     */
    @WrapMethod(method = DRAW)
    private static void avogadro$scope(ItemStack stack, Minecraft minecraft, List<String> lines, List<?> itemLines, int x, int y, int maxWidth, FontRenderer font, Consumer<Rectangle> layout, Operation<Void> original) {
        MoleculeTooltips.withTooltip(() -> original.call(stack, minecraft, lines, itemLines, x, y, maxWidth, font, layout));
    }

    /**
     * Wraps event bus post calls to intercept render tooltip pre and post text events.
     *
     * @param bus      event bus instance
     * @param event    event being posted
     * @param original original operation invocation
     * @return {@code true} if event was canceled, {@code false} otherwise
     */
    @WrapOperation(method = DRAW, at = @At(value = "INVOKE",
            target = "Lnet/minecraftforge/fml/common/eventhandler/EventBus;post(Lnet/minecraftforge/fml/common/eventhandler/Event;)Z"))
    private static boolean avogadro$events(EventBus bus, Event event, Operation<Boolean> original) {
        if (event instanceof RenderTooltipEvent.PostText text) MoleculeTooltips.beforePostText(text);
        boolean canceled = original.call(bus, event);
        if (event instanceof RenderTooltipEvent.Pre pre) MoleculeTooltips.afterPre(pre, canceled);
        return canceled;
    }

    /**
     * Modifies local tooltip height, width, and X position variables to reserve space for molecule tooltips.
     *
     * @param y           tooltip Y coordinate
     * @param height      tooltip height reference
     * @param width       tooltip width reference
     * @param x           tooltip X coordinate reference
     * @param screenWidth screen width in pixels
     * @return modified Y coordinate value
     */
    @ModifyVariable(method = DRAW, at = @At(value = "LOAD", ordinal = 0), name = "tooltipY")
    private static int avogadro$space(int y, @Local(name = "tooltipHeight") LocalIntRef height,
                                      @Local(name = "tooltipTextWidth") LocalIntRef width,
                                      @Local(name = "tooltipX") LocalIntRef x,
                                      @Local(name = "screenWidth") int screenWidth) {
        height.set(MoleculeTooltips.reserveHeight(height.get()));
        if (MoleculeTooltips.hasContent()) {
            width.set(MoleculeTooltips.minimumWidth(width.get()));
            x.set(Math.max(4, Math.min(x.get(), screenWidth - width.get() - 4)));
        }
        return y;
    }
}
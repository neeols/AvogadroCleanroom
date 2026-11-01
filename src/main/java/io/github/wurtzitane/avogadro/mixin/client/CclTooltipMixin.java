package io.github.wurtzitane.avogadro.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;
import io.github.wurtzitane.avogadro.client.MoleculeTooltips;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.event.RenderTooltipEvent;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.EventBus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.List;

/** Mixin targeting CodeChicken Lib's GuiDraw class to inject custom molecule tooltip scoping, event handling, and layout sizing. */
@Pseudo
@Mixin(targets = "codechicken.lib.gui.GuiDraw", remap = false)
public abstract class CclTooltipMixin {

    /** Target method signature string for CodeChicken Lib multi-line tooltip drawing. */
    private static final String DRAW = "drawMultiLineTip(Lnet/minecraft/item/ItemStack;IILjava/util/List;)V";

    /**
     * Wraps the multi-line tooltip drawing method to execute within a scoped molecule tooltip context.
     *
     * @param stack    item stack being rendered
     * @param x        render X coordinate
     * @param y        render Y coordinate
     * @param lines    tooltip text lines
     * @param original original operation invocation
     */
    @WrapMethod(method = DRAW)
    private static void avogadro$scope(ItemStack stack, int x, int y, List<String> lines, Operation<Void> original) {
        MoleculeTooltips.withTooltip(() -> original.call(stack, x, y, lines));
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
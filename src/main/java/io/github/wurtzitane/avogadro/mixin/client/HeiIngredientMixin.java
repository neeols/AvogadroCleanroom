package io.github.wurtzitane.avogadro.mixin.client;

import java.awt.Rectangle;
import java.util.List;
import java.util.function.Consumer;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import io.github.wurtzitane.avogadro.client.MoleculeTooltips;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Bind the ingredient at the exact draw call, excluding nested tooltip callbacks. */
@Pseudo
@Mixin(targets = "mezz.jei.gui.ingredients.GuiIngredient", remap = false)
public abstract class HeiIngredientMixin {

    /** Target method name string for GUI ingredient tooltip rendering. */
    private static final String DRAW = "drawTooltip";

    /**
     * Wraps item tooltip rendering in JEI ingredients to bind ingredient context.
     *
     * @param stack item stack instance
     * @param minecraft Minecraft client instance
     * @param lines tooltip lines list
     * @param x render X coordinate
     * @param y render Y coordinate
     * @param font font renderer instance
     * @param original original operation invocation
     * @param value ingredient value object
     */
    @WrapOperation(method = DRAW, at = @At(value = "INVOKE", target = "Lmezz/jei/gui/TooltipRenderer;drawHoveringText(Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/Minecraft;Ljava/util/List;IILnet/minecraft/client/gui/FontRenderer;)V"))
    private void avogadro$item(ItemStack stack, Minecraft minecraft, List<String> lines, int x, int y,
            FontRenderer font, Operation<Void> original, @Local(argsOnly = true) Object value) {
        MoleculeTooltips.withIngredient(value, () -> original.call(stack, minecraft, lines, x, y, font));
    }

    /**
     * Wraps fluid tooltip rendering in JEI ingredients to bind ingredient context.
     *
     * @param minecraft Minecraft client instance
     * @param lines tooltip lines list
     * @param x render X coordinate
     * @param y render Y coordinate
     * @param font font renderer instance
     * @param original original operation invocation
     * @param value ingredient value object
     */
    @WrapOperation(method = DRAW, at = @At(value = "INVOKE", target = "Lmezz/jei/gui/TooltipRenderer;drawHoveringText(Lnet/minecraft/client/Minecraft;Ljava/util/List;IILnet/minecraft/client/gui/FontRenderer;)V"))
    private void avogadro$fluid(Minecraft minecraft, List<String> lines, int x, int y, FontRenderer font,
            Operation<Void> original, @Local(argsOnly = true) Object value) {
        MoleculeTooltips.withIngredient(value, () -> original.call(minecraft, lines, x, y, font));
    }

    /**
     * Wraps rich tooltip rendering with items in JEI ingredients to bind ingredient context.
     *
     * @param stack item stack instance
     * @param minecraft Minecraft client instance
     * @param lines tooltip lines list
     * @param itemLines nested item lines list
     * @param x render X coordinate
     * @param y render Y coordinate
     * @param maxWidth maximum allowed width
     * @param font font renderer instance
     * @param layout layout rectangle consumer
     * @param original original operation invocation
     * @param value ingredient value object
     */
    @WrapOperation(method = DRAW, at = @At(value = "INVOKE", target = "Lmezz/jei/gui/TooltipRenderer;drawHoveringTextAndItems(Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/Minecraft;Ljava/util/List;Ljava/util/List;IIILnet/minecraft/client/gui/FontRenderer;Ljava/util/function/Consumer;)V"))
    private void avogadro$rich(ItemStack stack, Minecraft minecraft, List<String> lines, List<?> itemLines, int x,
            int y, int maxWidth, FontRenderer font, Consumer<Rectangle> layout, Operation<Void> original,
            @Local(argsOnly = true) Object value) {
        MoleculeTooltips.withIngredient(value,
                () -> original.call(stack, minecraft, lines, itemLines, x, y, maxWidth, font, layout));
    }
}

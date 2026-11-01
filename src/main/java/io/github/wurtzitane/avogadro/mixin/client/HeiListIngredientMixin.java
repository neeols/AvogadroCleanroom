package io.github.wurtzitane.avogadro.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.wurtzitane.avogadro.client.MoleculeTooltips;
import mezz.jei.bookmarks.BookmarkItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/** The same renderer serves the ingredient list and bookmarks. */
@Pseudo
@Mixin(targets = "mezz.jei.render.IngredientRenderer", remap = false)
public abstract class HeiListIngredientMixin {

    /**
     * Wraps ingredient tooltip rendering to bind unwrapped bookmark ingredients for molecule tooltips.
     *
     * @param stack      item stack instance
     * @param minecraft  Minecraft client instance
     * @param lines      tooltip lines list
     * @param x          render X coordinate
     * @param y          render Y coordinate
     * @param font       font renderer instance
     * @param original   original operation invocation
     * @param ingredient ingredient object from local variables
     */
    @WrapOperation(method = "drawTooltip(Lnet/minecraft/client/Minecraft;IILjava/util/List;)V",
            at = @At(value = "INVOKE", target =
                    "Lmezz/jei/gui/TooltipRenderer;drawHoveringText(Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/Minecraft;Ljava/util/List;IILnet/minecraft/client/gui/FontRenderer;)V"))
    private void avogadro$ingredient(ItemStack stack, Minecraft minecraft, List<String> lines, int x, int y,
                                     FontRenderer font, Operation<Void> original, @Local(name = "ingredient") Object ingredient) {
        Object displayed = BookmarkItem.unwrap(ingredient);
        MoleculeTooltips.withIngredient(displayed, () -> original.call(stack, minecraft, lines, x, y, font));
    }
}
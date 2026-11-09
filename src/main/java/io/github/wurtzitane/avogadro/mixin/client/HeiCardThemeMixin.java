package io.github.wurtzitane.avogadro.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import io.github.wurtzitane.avogadro.client.UiThemes;
import io.github.wurtzitane.avogadro.compat.jei.MoleculeCategory;

import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

import mezz.jei.api.recipe.IRecipeCategory;

/** Keeps delayed HEI card tooltips in their owning theme after the model draw has finished. */
@Pseudo
@Mixin(targets = "mezz.jei.gui.recipes.RecipeLayout", remap = false)
public abstract class HeiCardThemeMixin {
    @Shadow @Final private IRecipeCategory<?> recipeCategory;

    @WrapMethod(method = "drawOverlays")
    private void avogadro$card(Minecraft minecraft, int mouseX, int mouseY, Operation<Void> original) {
        if (!MoleculeCategory.UID.equals(recipeCategory.getUid())) {
            original.call(minecraft, mouseX, mouseY);
            return;
        }
        try (var ignored = UiThemes.scope("recipe_card", "root")) {
            original.call(minecraft, mouseX, mouseY);
        }
    }
}

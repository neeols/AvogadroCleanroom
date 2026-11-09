package io.github.wurtzitane.avogadro.mixin.client;

import com.cleanroommc.modularui.screen.RichTooltip;
import com.cleanroommc.modularui.screen.viewport.GuiContext;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import io.github.wurtzitane.avogadro.client.UiThemes;

import net.minecraft.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;

/** Keeps tooltip text readable independently of the menu's ordinary text colors. */
@Mixin(value = RichTooltip.class, remap = false)
public abstract class ModularTooltipThemeMixin {
    @WrapMethod(method = "draw(Lcom/cleanroommc/modularui/screen/viewport/GuiContext;Lnet/minecraft/item/ItemStack;)V")
    private void avogadro$tooltip(GuiContext context, ItemStack stack, Operation<Void> original) {
        try (var ignored = UiThemes.tooltip()) {
            original.call(context, stack);
        }
    }
}

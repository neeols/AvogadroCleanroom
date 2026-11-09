package io.github.wurtzitane.avogadro.mixin.client;

import io.github.wurtzitane.avogadro.client.UiThemes;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiTextField;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Applies cursor and selection colors only while an Avogadro configuration field is being drawn. */
@Mixin(GuiTextField.class)
public abstract class NativeInputThemeMixin {
    @ModifyConstant(method = "drawTextBox", constant = @Constant(intValue = -3092272))
    private int avogadro$cursor(int original) {
        return UiThemes.isScoped() ? UiThemes.color("input.cursor", original) : original;
    }

    @Inject(method = "drawSelectionBox", at = @At("HEAD"), cancellable = true)
    private void avogadro$selection(int left, int top, int right, int bottom, CallbackInfo callback) {
        if (UiThemes.isScoped()) {
            Gui.drawRect(left, top, right, bottom, UiThemes.color("input.selection", 0xFFA9C6D8));
            callback.cancel();
        }
    }
}

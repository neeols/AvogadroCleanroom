package io.github.wurtzitane.avogadro.mixin.client;

import io.github.wurtzitane.avogadro.client.UiThemes;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Separates the input cursor color from text while leaving other mods' fields unchanged. */
@Mixin(targets = "com.cleanroommc.modularui.widgets.textfield.TextFieldRenderer", remap = false)
public abstract class ModularCursorThemeMixin {
    @ModifyVariable(method = "setCursorColor", at = @At("HEAD"), argsOnly = true)
    private int avogadro$cursor(int color) {
        return UiThemes.isScoped() ? UiThemes.color("input.cursor", color) : color;
    }
}

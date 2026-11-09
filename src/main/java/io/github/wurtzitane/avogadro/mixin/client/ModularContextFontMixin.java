package io.github.wurtzitane.avogadro.mixin.client;

import io.github.wurtzitane.avogadro.client.PixelFont;

import net.minecraft.client.gui.FontRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Mixin targeting ModularUI's GuiContext class to inject custom pixel font overrides for UI rendering. */
@Mixin(targets = "com.cleanroommc.modularui.screen.viewport.GuiContext", remap = false)
public abstract class ModularContextFontMixin {

    /**
     * Injects custom pixel font logic into the font renderer return value for ModularUI contexts.
     *
     * @param result callback information returnable containing font renderer
     */
    @Inject(method = "getFontRenderer", at = @At("RETURN"), cancellable = true)
    private void avogadro$pixelFont(CallbackInfoReturnable<FontRenderer> result) {
        result.setReturnValue(PixelFont.forUi(result.getReturnValue()));
    }
}

package io.github.wurtzitane.avogadro.mixin.client;

import io.github.wurtzitane.avogadro.client.ViewerFramerate;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Mixin targeting Minecraft's main client class to dynamically throttle framerates when interactive viewers are active. */
@Mixin(Minecraft.class)
public abstract class ViewerFramerateMixin {

    /**
     * Injects custom framerate limit overrides when viewer framerate throttling is active.
     *
     * @param result callback information returnable containing framerate limit integer
     */
    @Inject(method = "getLimitFramerate", at = @At("RETURN"), cancellable = true)
    private void avogadro$viewerFramerate(CallbackInfoReturnable<Integer> result) {
        if (ViewerFramerate.isActive()) result.setReturnValue(ViewerFramerate.limit());
    }

    /**
     * Injects custom menu limit enablement checks when viewer framerate throttling is active.
     *
     * @param result callback information returnable containing boolean flag
     */
    @Inject(method = "isFramerateLimitBelowMax", at = @At("RETURN"), cancellable = true)
    private void avogadro$enableMenuLimit(CallbackInfoReturnable<Boolean> result) {
        if (ViewerFramerate.isActive()) result.setReturnValue(true);
    }
}
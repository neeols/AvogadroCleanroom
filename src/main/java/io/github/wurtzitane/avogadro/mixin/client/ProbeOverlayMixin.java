package io.github.wurtzitane.avogadro.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.wurtzitane.avogadro.compat.probe.ProbeHud;
import mcjty.theoneprobe.api.IElement;
import mcjty.theoneprobe.api.IOverlayStyle;
import mcjty.theoneprobe.api.ProbeMode;
import mcjty.theoneprobe.apiimpl.ProbeInfo;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import org.apache.commons.lang3.tuple.Pair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/** Mixin targeting The One Probe's OverlayRenderer class to inject custom HUD element registration, caching, and overlay rendering for molecules. */
@Pseudo
@Mixin(targets = "mcjty.theoneprobe.rendering.OverlayRenderer", remap = false)
public abstract class ProbeOverlayMixin {

    /** Cached probe information map mapping dimension and block position pairs to timestamps and probe info. */
    @Shadow private static Map<Pair<Integer, BlockPos>, Pair<Long, ProbeInfo>> cachedInfo;

    /**
     * Wraps entity HUD rendering to bind the current hit ray trace result for probe context.
     *
     * @3param mode     probe display mode
     * @param hit      ray trace result hit instance
     * @param width    overlay width
     * @param height   overlay height
     * @param original original operation invocation
     */
    @WrapMethod(method = "renderHUDEntity")
    private static void avogadro$entity(ProbeMode mode, RayTraceResult hit, double width, double height,
                                        Operation<Void> original) {
        ProbeHud.withEntity(hit, () -> original.call(mode, hit, width, height));
    }

    /**
     * Wraps block HUD rendering to bind the current hit ray trace result for probe context.
     *
     * @param mode     probe display mode
     * @param hit      ray trace result hit instance
     * @param width    overlay width
     * @param height   overlay height
     * @param original original operation invocation
     */
    @WrapMethod(method = "renderHUDBlock")
    private static void avogadro$target(ProbeMode mode, RayTraceResult hit, double width, double height,
                                        Operation<Void> original) {
        ProbeHud.withTarget(hit, () -> original.call(mode, hit, width, height));
    }

    /**
     * Injects logic at method return to record registered probe information coordinates.
     *
     * @param dimension dimension ID
     * @param pos       block position
     * @param info      probe info instance
     * @param callback  callback information instance
     */
    @Inject(method = "registerProbeInfo(ILnet/minecraft/util/math/BlockPos;Lmcjty/theoneprobe/apiimpl/ProbeInfo;)V", at = @At("RETURN"))
    private static void avogadro$origin(int dimension, BlockPos pos, ProbeInfo info, CallbackInfo callback) {
        ProbeHud.registered(dimension, pos, info);
    }

    /**
     * Wraps element rendering to inject custom molecule elements into the probe info overlay.
     *
     * @param info     probe info instance
     * @param style    overlay style instance
     * @param width    overlay width
     * @param height   overlay height
     * @param extra    extra element instance
     * @param original original operation invocation
     */
    @WrapMethod(method = "renderElements")
    private static void avogadro$element(ProbeInfo info, IOverlayStyle style, double width, double height,
                                         IElement extra, Operation<Void> original) {
        IElement molecule = ProbeHud.element(info, cachedInfo);
        if (molecule == null) {
            original.call(info, style, width, height, extra);
            return;
        }
        info.element(molecule);
        try {
            original.call(info, style, width, height, extra);
        } finally {
            info.removeElement(molecule);
            ProbeHud.finished();
        }
    }
}
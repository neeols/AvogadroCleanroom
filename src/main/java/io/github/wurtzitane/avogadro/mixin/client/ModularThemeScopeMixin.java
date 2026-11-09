package io.github.wurtzitane.avogadro.mixin.client;

import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import io.github.wurtzitane.avogadro.client.UiThemes;

import org.spongepowered.asm.mixin.Mixin;

/** Scopes native widget drawing, including the separate background and tooltip passes. */
@Mixin(targets = "com.cleanroommc.modularui.widget.InternalWidgetTree", remap = false)
public abstract class ModularThemeScopeMixin {
    @WrapMethod(method = "drawTree")
    private static void avogadro$tree(IWidget widget, ModularGuiContext context, boolean ignoreEnabled,
            boolean background, Operation<Void> original) {
        try (var ignored = UiThemes.widget(widget)) {
            original.call(widget, context, ignoreEnabled, background);
        }
    }

    @WrapMethod(method = "drawBackground")
    private static void avogadro$background(IWidget widget, ModularGuiContext context, boolean ignoreEnabled,
            Operation<Void> original) {
        try (var ignored = UiThemes.widget(widget)) {
            original.call(widget, context, ignoreEnabled);
        }
    }

    @WrapMethod(method = "drawTreeForeground")
    private static void avogadro$foreground(IWidget widget, ModularGuiContext context, Operation<Void> original) {
        try (var ignored = UiThemes.widget(widget)) {
            original.call(widget, context);
        }
    }
}

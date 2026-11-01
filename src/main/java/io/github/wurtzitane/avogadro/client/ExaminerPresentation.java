package io.github.wurtzitane.avogadro.client;

import com.cleanroommc.modularui.drawable.GuiDraw;
import com.cleanroommc.modularui.screen.RichTooltip;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

/**
 * Provides small shared presentation hints, help icons, and zoom feedback for views.
 */
public final class ExaminerPresentation {

    /** Private constructor to prevent instantiation of utility class. */
    private ExaminerPresentation() {}

    /**
     * Draws the shared information icon within a 14 by 14 area.
     *
     * @param x       x coordinate position
     * @param y       y coordinate position
     * @param hovered whether the icon is currently hovered
     * @param color   base ARGB color integer
     */
    public static void drawInfoIcon(int x, int y, boolean hovered, int color) {
        int ink = 0xFF000000 | (hovered ? StructureStyle.ACCENT : color);
        boolean texture = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        boolean alpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        int shade = GL11.glGetInteger(GL11.GL_SHADE_MODEL);
        int src = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), dst = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        int srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        try {
            GuiDraw.drawCircle(x + 1, y + 1, 12, ink, 32);
            GuiDraw.drawRect(x + 6, y + 4, 2, 1, StructureStyle.PANEL);
            GuiDraw.drawRect(x + 6, y + 6, 2, 4, StructureStyle.PANEL);
        } finally {
            if (texture) {
                GlStateManager.enableTexture2D();
            }
            if (alpha) {
                GlStateManager.enableAlpha();
            }
            GlStateManager.shadeModel(shade);
            GlStateManager.tryBlendFuncSeparate(src, dst, srcAlpha, dstAlpha);
            if (!blend) {
                GlStateManager.disableBlend();
            }
            GlStateManager.color(1, 1, 1, 1);
        }
    }

    /** Creates a controls widget displaying shared examiner help tooltips. */
    static Widget<?> controls(boolean atomNavigation, int color) {
        return new ControlsWidget(atomNavigation, color).size(14, 14);
    }

    /**
     * Widget displaying help tooltips for examiner controls.
     */
    static final class ControlsWidget extends Widget<ControlsWidget> {
        private final int color;

        /** Constructs a new {@link ControlsWidget}. */
        ControlsWidget(boolean atomNavigation, int color) {
            this.color = color;
            tooltip(tooltip -> {
                tooltip.addLine(I18n.format("avogadro.examiner.help"))
                        .addLine(I18n.format("avogadro.examiner.help.rotate"))
                        .addLine(I18n.format("avogadro.examiner.help.pan"))
                        .addLine(I18n.format("avogadro.examiner.help.zoom"))
                        .addLine(I18n.format("avogadro.examiner.help.pause", AvogadroKeys.PAUSE_MOLECULE.getDisplayName()))
                        .addLine(I18n.format("avogadro.examiner.help.reset"));
                if (atomNavigation) {
                    tooltip.addLine(I18n.format("avogadro.examiner.help.inspect"))
                            .addLine(I18n.format("avogadro.examiner.help.next_atom"));
                }
            });
            tooltipPos(RichTooltip.Pos.LEFT);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void draw(ModularGuiContext context, WidgetThemeEntry<?> theme) {
            drawInfoIcon(0, 0, isHovering(), color);
        }
    }

    /**
     * Handles zoom level feedback animations and overlays.
     */
    static final class ZoomFeedback {
        private static final long FADE_IN = 120_000_000L, HOLD = 850_000_000L, END = 1_200_000_000L;
        private long changedAt = Long.MIN_VALUE;
        private float startOpacity;

        /** Triggers a zoom level change feedback update. */
        void changed(long now) {
            startOpacity = opacity(now);
            changedAt = now;
        }

        /** Clears the zoom feedback timer. */
        void clear() {
            changedAt = Long.MIN_VALUE;
        }

        /** Calculates current opacity factor based on elapsed time. */
        float opacity(long now) {
            if (changedAt == Long.MIN_VALUE) {
                return 0;
            }
            long elapsed = Math.max(0, now - changedAt);
            if (elapsed < FADE_IN) {
                return startOpacity + (1 - startOpacity) * elapsed / FADE_IN;
            }
            if (elapsed <= HOLD) {
                return 1;
            }
            if (elapsed >= END) {
                return 0;
            }
            return (float) (END - elapsed) / (END - HOLD);
        }

        /** Draws the zoom percentage overlay. */
        void draw(float zoom, int width, int height, int color) {
            int alpha = Math.round(opacity(System.nanoTime()) * 255);
            if (alpha < 26) {
                return;
            }
            var font = PixelFont.renderer();
            String label = Math.round(zoom * 100) + "%";
            boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
            int src = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), dst = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
            int srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
            try {
                GlStateManager.enableBlend();
                GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                        GL11.GL_ONE, GL11.GL_ZERO);
                font.drawString(label, width - font.getStringWidth(label) - 8, height - font.FONT_HEIGHT - 7,
                        alpha << 24 | color & 0xFFFFFF);
            } finally {
                GlStateManager.tryBlendFuncSeparate(src, dst, srcAlpha, dstAlpha);
                if (!blend) {
                    GlStateManager.disableBlend();
                }
                GlStateManager.color(1, 1, 1, 1);
            }
        }
    }
}
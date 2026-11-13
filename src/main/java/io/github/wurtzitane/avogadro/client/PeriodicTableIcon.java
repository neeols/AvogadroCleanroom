package io.github.wurtzitane.avogadro.client;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import io.github.wurtzitane.avogadro.api.ElementCatalog;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.client.model.pipeline.UnpackedBakedQuad;

final class PeriodicTableIcon {
    private static long elements = -1, palette = -1;
    private static List<BakedQuad> quads;

    private PeriodicTableIcon() {}

    static BufferedImage image() {
        var image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 2; y < 14; y++)
            for (int x = 0; x < 16; x++)
                image.setRGB(x, y, 0xFFFFFFFF);
        var layout = PeriodicTableLayout.fit(1920, Math.round(PeriodicTableLayout.heightUnits() * 100));
        for (var cell : layout.cells()) {
            int x, y;
            if (cell.row() < 7) {
                int column = cell.column();
                x = 1 + (column < 2 ? column : column < 12 ? 2 + (column - 2) * 6 / 10 : column - 4);
                y = cell.row() + 3;
            } else if (cell.row() < 9) {
                x = cell.column() - 2;
                y = cell.row() + 4;
            } else {
                continue;
            }
            if (x < 1 || x > 14 || y > 14)
                continue;
            var element = ElementCatalog.byAtomicNumber(cell.atomicNumber());
            try (var ignored = UiThemes.element("element/" + element.symbol())) {
                image.setRGB(x, y, iconColor(ElementCard.color(element.symbol())));
            }
        }
        return image;
    }

    static List<BakedQuad> quads() {
        long currentElements = ElementCatalog.revision(), currentPalette = UiThemes.paintRevision();
        if (quads == null || elements != currentElements || palette != currentPalette) {
            quads = bake(image(), ModelLoader.White.INSTANCE);
            elements = currentElements;
            palette = currentPalette;
        }
        return quads;
    }

    static List<BakedQuad> bake(BufferedImage image, TextureAtlasSprite sprite) {
        var result = new ArrayList<BakedQuad>();
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16;) {
                int color = image.getRGB(x, y), end = x + 1;
                while (end < 16 && image.getRGB(end, y) == color)
                    end++;
                if ((color >>> 24) == 0) {
                    x = end;
                    continue;
                }
                var builder = new UnpackedBakedQuad.Builder(DefaultVertexFormats.ITEM);
                builder.setTexture(sprite);
                builder.setQuadOrientation(EnumFacing.SOUTH);
                builder.setApplyDiffuseLighting(false);
                vertex(builder, x / 16f, 1 - (y + 1) / 16f, color, sprite);
                vertex(builder, end / 16f, 1 - (y + 1) / 16f, color, sprite);
                vertex(builder, end / 16f, 1 - y / 16f, color, sprite);
                vertex(builder, x / 16f, 1 - y / 16f, color, sprite);
                result.add(builder.build());
                x = end;
            }
        }
        return List.copyOf(result);
    }

    static void clear() {
        quads = null;
    }

    private static int iconColor(int color) {
        float[] hsb = Color.RGBtoHSB(color >>> 16 & 255, color >>> 8 & 255, color & 255, null);
        if (hsb[1] > .04f)
            hsb[1] = Math.min(1, Math.max(.30f, hsb[1] * 1.25f));
        return Color.HSBtoRGB(hsb[0], hsb[1], hsb[2]);
    }

    private static void vertex(UnpackedBakedQuad.Builder builder, float x, float y, int color,
            TextureAtlasSprite sprite) {
        var format = DefaultVertexFormats.ITEM;
        for (int i = 0; i < format.getElementCount(); i++) {
            switch (format.getElement(i).getUsage()) {
                case POSITION -> builder.put(i, x, y, .5f, 1);
                case COLOR ->
                    builder.put(i, (color >>> 16 & 255) / 255f, (color >>> 8 & 255) / 255f, (color & 255) / 255f, 1);
                case NORMAL -> builder.put(i, 0, 0, 1, 0);
                case UV -> builder.put(i, sprite.getInterpolatedU(8), sprite.getInterpolatedV(8), 0, 1);
                default -> builder.put(i);
            }
        }
    }
}

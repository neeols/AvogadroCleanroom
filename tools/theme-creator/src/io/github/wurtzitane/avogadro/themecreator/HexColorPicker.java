package io.github.wurtzitane.avogadro.themecreator;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.IntConsumer;

import javax.swing.JPanel;

/** Compact saturation/brightness picker and hue strip; the editor supplies its hex and opacity fields. */
final class HexColorPicker extends JPanel {
    private float hue;
    private float saturation;
    private float brightness;
    /** The RGB picker preserves opacity; the separate editor slider changes it. */
    private int alpha = 255;
    private final IntConsumer changed;

    /**
     * Creates a picker whose callbacks update the owning draft on the Swing event-dispatch thread. Gesture callbacks
     * let the editor group a full drag into one undo step.
     */
    HexColorPicker(IntConsumer changed) {
        this(changed, () -> {}, () -> {});
    }

    /**
     * Creates a picker whose callbacks update the owning draft on the Swing event-dispatch thread. Gesture callbacks
     * let the editor group a full drag into one undo step.
     */
    HexColorPicker(IntConsumer changed, Runnable begin, Runnable end) {
        this.changed = changed;
        getAccessibleContext().setAccessibleName("Hue, saturation and brightness picker");
        setPreferredSize(new Dimension(280, 170));
        setMinimumSize(new Dimension(160, 100));
        setToolTipText("Pick a hue on the right, then saturation and brightness in the large square.");
        MouseAdapter mouse = new MouseAdapter() {
            private boolean hueDrag;
            @Override
            public void mousePressed(MouseEvent event) {
                if (!isEnabled()) {
                    return;
                }
                begin.run();
                hueDrag = event.getX() >= getWidth() - 26;
                pick(event);
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                if (!isEnabled()) {
                    return;
                }
                pick(event);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                end.run();
            }

            /** Clamps drags beyond the panel edges while retaining the region chosen on press. */
            private void pick(MouseEvent event) {
                if (hueDrag)
                    hue = Math.clamp(event.getY() / (float) Math.max(1, getHeight() - 1), 0, 1);
                else {
                    saturation = Math.clamp(event.getX() / (float) Math.max(1, getWidth() - 38), 0, 1);
                    brightness = 1 - Math.clamp(event.getY() / (float) Math.max(1, getHeight() - 1), 0, 1);
                }
                changed.accept(alpha << 24 | Color.HSBtoRGB(hue, saturation, brightness) & 0xffffff);
                repaint();
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    /**
     * Updates the displayed ARGB selection without emitting a user edit. Achromatic colors retain the previous hue,
     * avoiding a sudden hue reset when choosing gray.
     */
    void color(int argb) {
        float[] hsv = Color.RGBtoHSB(argb >> 16 & 255, argb >> 8 & 255, argb & 255, null);
        if (hsv[1] > 0)
            hue = hsv[0];
        saturation = hsv[1];
        brightness = hsv[2];
        alpha = argb >>> 24;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            int width = getWidth() - 38, height = getHeight();
            g.setPaint(new GradientPaint(0, 0, Color.WHITE, width, 0, Color.getHSBColor(hue, 1, 1)));
            g.fillRect(0, 0, width, height);
            g.setPaint(new GradientPaint(0, 0, new Color(0, 0, 0, 0), 0, height, Color.BLACK));
            g.fillRect(0, 0, width, height);
            for (int i = 0; i < 6; i++) {
                int top = i * height / 6, bottom = (i + 1) * height / 6;
                g.setPaint(new GradientPaint(0, top, Color.getHSBColor(i / 6f, 1, 1), 0, bottom,
                        Color.getHSBColor((i + 1) / 6f, 1, 1)));
                g.fillRect(getWidth() - 24, top, 24, bottom - top);
            }
            int x = Math.round(saturation * (width - 1)), y = Math.round((1 - brightness) * (height - 1));
            g.setColor(Color.BLACK);
            g.drawOval(x - 5, y - 5, 10, 10);
            g.setColor(Color.WHITE);
            g.drawOval(x - 4, y - 4, 8, 8);
            int hy = Math.round(hue * (height - 1));
            g.setColor(Color.WHITE);
            g.drawRect(getWidth() - 25, hy - 2, 24, 4);
            g.setColor(Color.BLACK);
            g.drawRect(getWidth() - 26, hy - 3, 26, 6);
        } finally {
            g.dispose();
        }
    }
}

package io.github.wurtzitane.avogadro.themecreator;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.imageio.ImageIO;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingWorker;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.wurtzitane.avogadro.theme.ThemeDefinition;

/**
 * Actual production-menu captures with semantic-color masks. The creator recolors those masks; it does not maintain
 * another implementation of Minecraft menus, layouts, or chemistry.
 */
final class PreviewAtlas {
    private static final int[] SHIFTS = {0, 8, 16};
    /** One native semantic property; id is an RGB hit-mask key, not a theme color. */
    record Slot(int id, String screen, String element, String role, int color) {
    }

    /** Native black/white response map used to preserve particle lighting during recoloring. */
    record Basis(Slot slot, String weights) {
    }

    /** Capture assets and immutable authoring targets for one menu tab and its hover state. */
    record Tab(String id, String title, String screen, String normal, String normalMask, String hover, String hoverMask,
            List<Slot> slots, List<Basis> basis) {
    }

    /**
     * Loads immutable tab and color-slot metadata from bundled captures. Pixel decoding is deferred until a tab is
     * viewed so opening the editor does not decode every image.
     */
    static List<Tab> read() throws IOException {
        try (var stream = PreviewAtlas.class.getResourceAsStream("/previews/index.json")) {
            if (stream == null) {
                throw new IOException("The Theme Creator download is missing its menu previews.");
            }
            var json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            List<Tab> tabs = new ArrayList<>();
            for (var value : json.getAsJsonArray("previews")) {
                JsonObject tab = value.getAsJsonObject();
                List<Slot> slots = new ArrayList<>();
                for (var entry : tab.getAsJsonArray("samples")) {
                    var s = entry.getAsJsonObject();
                    slots.add(new Slot(s.get("id").getAsInt(), s.get("screen").getAsString(),
                            s.get("element").getAsString(), s.get("role").getAsString(),
                            ThemeDefinition.hex(s.get("color").getAsString())));
                }
                List<Basis> basis = new ArrayList<>();
                if (tab.has("basis"))
                    for (var item : tab.getAsJsonArray("basis")) {
                        var b = item.getAsJsonObject();
                        basis.add(new Basis(
                                new Slot(-1, b.get("screen").getAsString(), b.get("element").getAsString(),
                                        b.get("role").getAsString(), ThemeDefinition.hex(b.get("color").getAsString())),
                                b.get("weights").getAsString()));
                    }
                tabs.add(new Tab(tab.get("id").getAsString(), tab.get("title").getAsString(),
                        tab.get("screen").getAsString(), tab.get("normal").getAsString(),
                        tab.get("normal_mask").getAsString(), tab.get("hover").getAsString(),
                        tab.get("hover_mask").getAsString(), List.copyOf(slots), List.copyOf(basis)));
            }
            return List.copyOf(tabs);
        }
    }

    /** Lazy, event-thread-owned preview with independent navigation and revision-keyed raster caching. */
    static final class Canvas extends JPanel {
        private final Tab tab;
        private final Consumer<Slot> selected;
        private Raster normal;
        private Raster hover;
        private boolean hoverState;
        private boolean loading;
        private ThemeDraft draft;
        private long renderedRevision = -1;
        private BufferedImage rendered;
        private String selectedElement;
        /** Last painted transform, reused by hit testing so selection follows zoom and pan exactly. */
        private double scale = 1;
        private int left, top;
        private String message = "Select this tab to load its preview.";
        private Supplier<JPopupMenu> contextMenu;
        private boolean fitted = true;
        private boolean panning;
        private double zoom = 1;
        private int panX;
        private int panY;
        private int dragX;
        private int dragY;
        private Raster boundsRaster;
        private String boundsElement;
        private java.awt.Rectangle bounds;

        Canvas(Tab tab, Consumer<Slot> selected) {
            this.tab = tab;
            this.selected = selected;
            setPreferredSize(new Dimension(760, 520));
            setBackground(new Color(0x151515));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent event) {
                    requestFocusInWindow();
                    if (javax.swing.SwingUtilities.isMiddleMouseButton(event)) {
                        panning = true;
                        dragX = event.getX();
                        dragY = event.getY();
                        return;
                    }
                    Raster raster = hoverState ? hover : normal;
                    if (raster == null) {
                        return;
                    }
                    int x = (int) Math.floor((event.getX() - left) / scale);
                    int y = (int) Math.floor((event.getY() - top) / scale);
                    if (x < 0 || y < 0 || x >= raster.width || y >= raster.height) {
                        return;
                    }
                    int index = raster.slotIndices[y * raster.width + x];
                    for (int i = 0; i < raster.weights.length; i++) {
                        if ((raster.weights[i][y * raster.width + x] & 0xffffff) != 0) {
                            var slot = tab.basis().get(i).slot();
                            selectedElement = slot.element();
                            selected.accept(slot);
                            popup(event);
                            repaint();
                            return;
                        }
                    }
                    if (index >= 0) {
                        var slot = tab.slots().get(index);
                        selectedElement = slot.element();
                        selected.accept(slot);
                        popup(event);
                        repaint();
                    }
                }

                @Override
                public void mouseReleased(MouseEvent event) {
                    panning = false;
                }
            });
            addMouseMotionListener(new MouseAdapter() {
                @Override
                public void mouseDragged(MouseEvent event) {
                    if (!panning) {
                        return;
                    }
                    panX += event.getX() - dragX;
                    panY += event.getY() - dragY;
                    dragX = event.getX();
                    dragY = event.getY();
                    repaint();
                }
            });
            addMouseWheelListener(event -> {
                Raster raster = hoverState ? hover : normal;
                if (raster == null) {
                    return;
                }
                double next = Math.clamp(scale * Math.pow(1.15, -event.getPreciseWheelRotation()), .2, 5);
                double imageX = (event.getX() - left) / scale, imageY = (event.getY() - top) / scale;
                panX = (int) Math.round(event.getX() - imageX * next - (getWidth() - raster.width * next) / 2);
                panY = (int) Math.round(event.getY() - imageY * next - (getHeight() - raster.height * next) / 2);
                zoom = next;
                fitted = false;
                repaint();
            });
            setFocusable(true);
            getAccessibleContext().setAccessibleName("Interactive Minecraft menu preview");
            setToolTipText(
                    "Click a part to edit its color. Right-click for color actions. Wheel zooms; middle-drag pans.");
        }

        void contextMenu(Supplier<JPopupMenu> factory) {
            contextMenu = factory;
        }

        private void popup(MouseEvent event) {
            if (contextMenu != null && javax.swing.SwingUtilities.isRightMouseButton(event))
                contextMenu.get().show(this, event.getX(), event.getY());
        }

        /** Zero chooses Fit; positive values are image-to-screen scale, independent of color edits. */
        void zoom(double value) {
            fitted = value <= 0;
            zoom = Math.clamp(value, .2, 5);
            panX = panY = 0;
            repaint();
        }

        void selection(String element) {
            selectedElement = element;
            repaint();
        }

        void refresh(ThemeDraft draft, boolean hoverState) {
            if (this.draft != draft)
                renderedRevision = -1;
            this.draft = draft;
            if (this.hoverState != hoverState) {
                this.hoverState = hoverState;
                renderedRevision = -1;
            }
            if (normal == null && !loading) {
                loading = true;
                message = "Loading menu preview…";
                new SwingWorker<Raster[], Void>() {
                    @Override
                    protected Raster[] doInBackground() throws Exception {
                        return new Raster[]{load(tab.normal(), tab.normalMask(), tab),
                                load(tab.hover(), tab.hoverMask(), tab)};
                    }

                    @Override
                    protected void done() {
                        try {
                            var data = get();
                            normal = data[0];
                            hover = data[1];
                            message = "";
                        } catch (Exception failure) {
                            message = "Cannot load preview: " + failure.getMessage();
                        }
                        loading = false;
                        repaint();
                    }
                }.execute();
            }
            repaint();
        }

        /**
         * Recolors only when the draft revision changes. Native response weights retain lit particles; exact semantic
         * masks replace flat UI colors afterward. Zoom and selection are drawn last, so navigation does not require
         * recoloring or allocating another raster.
         */
        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Raster raster = hoverState ? hover : normal;
            if (raster == null || draft == null) {
                graphics.setColor(Color.LIGHT_GRAY);
                graphics.drawString(message, 20, 30);
                return;
            }
            if (renderedRevision != draft.revision() || rendered == null) {
                int[] colors = new int[tab.slots().size()];
                for (int i = 0; i < colors.length; i++) {
                    Slot slot = tab.slots().get(i);
                    colors[i] = draft.compiled().color(slot.role(), slot.screen(), slot.element(), slot.color());
                }
                if (rendered == null || rendered.getWidth() != raster.width || rendered.getHeight() != raster.height)
                    rendered = new BufferedImage(raster.width, raster.height, BufferedImage.TYPE_INT_ARGB);
                int[] output = ((java.awt.image.DataBufferInt) rendered.getRaster().getDataBuffer()).getData();
                System.arraycopy(raster.pixels, 0, output, 0, output.length);
                // Native response images preserve lighting, translucent clouds and overlapping particles.
                for (int basis = 0; basis < raster.weights.length; basis++) {
                    Slot slot = tab.basis().get(basis).slot();
                    int replacement = draft.compiled().color(slot.role(), slot.screen(), slot.element(), slot.color());
                    if (replacement == slot.color()) {
                        continue;
                    }
                    for (int pixel = 0; pixel < output.length; pixel++) {
                        int weight = raster.weights[basis][pixel];
                        if ((weight & 0xffffff) == 0) {
                            continue;
                        }
                        int color = 0xff000000;
                        for (int shift : SHIFTS) {
                            int delta = (replacement >> shift & 255) - (slot.color() >> shift & 255);
                            int value = (output[pixel] >> shift & 255)
                                    + Math.round((weight >> shift & 255) * delta / 255f);
                            color |= Math.clamp(value, 0, 255) << shift;
                        }
                        output[pixel] = color;
                    }
                }
                for (int i = 0; i < output.length; i++)
                    if (raster.slotIndices[i] >= 0) {
                        int color = colors[raster.slotIndices[i]];
                        int alpha = color >>> 24;
                        if (alpha == 255)
                            output[i] = color;
                        else {
                            // Captures have no live framebuffer behind them. Composite translucent
                            // flat-mask colors over a fixed neutral backing, then keep output opaque.
                            int backdrop = 0xff202020;
                            int red = (((color >> 16) & 255) * alpha + ((backdrop >> 16) & 255) * (255 - alpha)) / 255;
                            int green = (((color >> 8) & 255) * alpha + ((backdrop >> 8) & 255) * (255 - alpha)) / 255;
                            int blue = ((color & 255) * alpha + (backdrop & 255) * (255 - alpha)) / 255;
                            output[i] = 0xff000000 | red << 16 | green << 8 | blue;
                        }
                    }
                renderedRevision = draft.revision();
            }
            scale = fitted
                    ? Math.max(.01,
                            Math.min(1,
                                    Math.min((getWidth() - 24) / (double) raster.width,
                                            (getHeight() - 24) / (double) raster.height)))
                    : zoom;
            int displayWidth = (int) Math.round(raster.width * scale);
            int displayHeight = (int) Math.round(raster.height * scale);
            left = (getWidth() - displayWidth) / 2 + panX;
            top = (getHeight() - displayHeight) / 2 + panY;
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g.drawImage(rendered, left, top, displayWidth, displayHeight, null);
                if (selectedElement != null) {
                    java.awt.Rectangle rectangle = selectionBounds(raster);
                    if (rectangle != null) {
                        g.setColor(new Color(0x69b9ef));
                        g.drawRect(left + (int) (rectangle.x * scale) - 2, top + (int) (rectangle.y * scale) - 2,
                                (int) (rectangle.width * scale) + 4, (int) (rectangle.height * scale) + 4);
                    }
                }
            } finally {
                g.dispose();
            }
        }

        /** Selection pixels stay fixed while colors or zoom change; cache their bounds rather than rescanning. */
        private java.awt.Rectangle selectionBounds(Raster raster) {
            if (boundsRaster == raster && java.util.Objects.equals(boundsElement, selectedElement)) {
                return bounds;
            }
            boundsRaster = raster;
            boundsElement = selectedElement;
            int minX = raster.width;
            int minY = raster.height;
            int maxX = -1;
            int maxY = -1;
            for (int i = 0; i < raster.slotIndices.length; i++) {
                int index = raster.slotIndices[i];
                boolean match = index >= 0 && tab.slots().get(index).element().equals(selectedElement);
                for (int basis = 0; !match && basis < raster.weights.length; basis++)
                    match = tab.basis().get(basis).slot().element().equals(selectedElement)
                            && (raster.weights[basis][i] & 0xffffff) != 0;
                if (match) {
                    minX = Math.min(minX, i % raster.width);
                    minY = Math.min(minY, i / raster.width);
                    maxX = Math.max(maxX, i % raster.width);
                    maxY = Math.max(maxY, i / raster.width);
                }
            }
            return bounds = maxX < 0 ? null : new java.awt.Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1);
        }
    }

    /** Decoded native pixels, O(1) mask lookup and per-channel particle response weights. */
    private record Raster(int width, int height, int[] pixels, int[] slotIndices, int[][] weights) {
    }

    /**
     * Decodes one capture, maps mask RGB identifiers to slot indices and checks response-image dimensions. Unrecognized
     * pixels retain their native artwork and shading.
     */
    private static Raster load(String image, String mask, Tab tab) throws IOException {
        List<Slot> slots = tab.slots();
        BufferedImage base = readImage(image);
        BufferedImage ids = readImage(mask);
        if (base.getWidth() != ids.getWidth() || base.getHeight() != ids.getHeight()) {
            throw new IOException("Preview and color mask dimensions differ");
        }
        int w = base.getWidth(), h = base.getHeight();
        int[] samples = ids.getRGB(0, 0, w, h, null, 0, w);
        Map<Integer, Integer> lookup = new HashMap<>();
        for (int i = 0; i < slots.size(); i++)
            lookup.put(slots.get(i).id() & 0xffffff, i);
        for (int i = 0; i < samples.length; i++)
            samples[i] = lookup.getOrDefault(samples[i] & 0xffffff, -1);
        int[][] weights = new int[tab.basis().size()][];
        for (int i = 0; i < weights.length; i++) {
            var response = readImage(tab.basis().get(i).weights());
            if (response.getWidth() != w || response.getHeight() != h) {
                throw new IOException("Atomic response and preview dimensions differ");
            }
            weights[i] = response.getRGB(0, 0, w, h, null, 0, w);
        }
        return new Raster(w, h, base.getRGB(0, 0, w, h, null, 0, w), samples, weights);
    }

    /**
     * Reads a bundled PNG beneath the preview resource root. Rejects parent/absolute paths and missing or undecodable
     * resources with an actionable load error.
     */
    private static BufferedImage readImage(String name) throws IOException {
        if (name.contains("..") || name.startsWith("/")) {
            throw new IOException("Invalid preview image path");
        }
        try (var stream = PreviewAtlas.class.getResourceAsStream("/previews/" + name)) {
            if (stream == null) {
                throw new IOException("Missing preview: " + name);
            }
            var image = ImageIO.read(stream);
            if (image == null) {
                throw new IOException("Invalid preview image: " + name);
            }
            return image;
        }
    }
}

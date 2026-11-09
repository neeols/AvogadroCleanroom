package io.github.wurtzitane.avogadro.client;

import java.util.Map;

import io.github.wurtzitane.avogadro.Config;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.Tessellator;
import net.minecraftforge.fml.client.config.GuiConfig;
import net.minecraftforge.fml.client.config.GuiSelectString;
import net.minecraftforge.fml.client.config.GuiSelectStringEntries;
import net.minecraftforge.fml.client.config.IConfigElement;

/** Styles Forge's existing value selector; Forge retains selection, reset, undo, and saving behavior. */
final class ThemeOptions {
    private ThemeOptions() {}

    static boolean isOpen() { return Minecraft.getMinecraft().currentScreen instanceof Selection; }

    static void open(GuiConfig parent, IConfigElement element, int slot, Map<Object, String> choices,
            Object current, boolean enabled) {
        Minecraft.getMinecraft().displayGuiScreen(new Selection(parent, element, slot, choices, current, enabled));
    }

    private static final class Selection extends GuiSelectString {
        private io.github.wurtzitane.avogadro.theme.ThemeStore.Order sortOrder = io.github.wurtzitane.avogadro.theme.ThemeStore.Order.RAINBOW;
        private boolean pinDefaults = true;
        Selection(GuiConfig parent, IConfigElement element, int slot, Map<Object, String> choices,
                Object current, boolean enabled) {
            super(parent, element, slot, new java.util.LinkedHashMap<>(choices), current, enabled);
        }

        @Override
        public void initGui() {
            super.initGui();
            reorder();
            entryList = new Entries(this, configElement, selectableValues);
            buttonList.add(new net.minecraftforge.fml.client.config.GuiButtonExt(601, width / 2 - 100, 52, 200, 18, sortLabel()));
        }

        /** Native reset/undo rebuilds the list; keep its presentation after either action. */
        @Override protected void actionPerformed(net.minecraft.client.gui.GuiButton button) {
            if (button.id == 601) {
                var orders = io.github.wurtzitane.avogadro.theme.ThemeStore.Order.values();
                sortOrder = orders[(sortOrder.ordinal() + 1) % orders.length];
                pinDefaults = false;
                reorder();
                entryList = new Entries(this, configElement, selectableValues);
                button.displayString = sortLabel();
                return;
            }
            super.actionPerformed(button);
            if (Minecraft.getMinecraft().currentScreen == this && !(entryList instanceof Entries))
                entryList = new Entries(this, configElement, selectableValues);
            applySelectedTheme();
        }

        private String sortLabel() {
            return net.minecraft.client.resources.I18n.format("avogadro.theme.sort",
                    net.minecraft.client.resources.I18n.format("avogadro.theme.order." + sortOrder.name().toLowerCase(java.util.Locale.ROOT)));
        }

        private void reorder() {
            selectableValues.clear();
            UiThemes.available(sortOrder, pinDefaults).forEach((id, entry) -> selectableValues.put(id, entry.definition().name()));
        }

        /** Save only this selection so previews and the parent option agree immediately. */
        private void applySelectedTheme() {
            entryList.saveChanges();
            String selected = String.valueOf(currentValue);
            if (!selected.equals(Config.UI_THEME.get())) {
                Config.configuration.getCategory(Config.APPEARANCE).get("theme").set(selected);
                Config.sync();
                UiThemes.reload();
            }
        }

        @Override
        public void drawDefaultBackground() {
            Gui.drawRect(0, 0, width, height, StructureStyle.PANEL());
        }

        @Override
        public void drawCenteredString(FontRenderer font, String text, int x, int y, int color) {
            var pixels = PixelFont.wrap(font);
            pixels.drawString(text, x - pixels.getStringWidth(text) / 2, y, StructureStyle.INK());
        }

        @Override
        public void drawScreen(int x, int y, float partial) {
            try (var ignored = UiThemes.scope("theme_selector", "root")) {
                super.drawScreen(x, y, partial);
                // Replace Forge's three-line heading and list-edge fades with the shared flat chrome.
                Gui.drawRect(0, 0, width, entryList.top, StructureStyle.PANEL());
                Gui.drawRect(0, entryList.bottom, width, height, StructureStyle.PANEL());
                Gui.drawRect(8, entryList.top - 1, width - 8, entryList.top, StructureStyle.RULE());
                Gui.drawRect(8, entryList.bottom, width - 8, entryList.bottom + 1, StructureStyle.RULE());
                var font = PixelFont.renderer();
                String heading = net.minecraft.client.resources.I18n.format("avogadro.configuration.theme");
                font.drawString(heading, (width - font.getStringWidth(heading)) / 2, 16,
                        UiThemes.color("text.title", StructureStyle.INK));
                String help = net.minecraft.client.resources.I18n.format("avogadro.theme.choose");
                font.drawString(help, (width - font.getStringWidth(help)) / 2, 34, StructureStyle.MUTED());
                for (var button : buttonList)
                    if (button.visible)
                        try (var control = UiThemes.scope("theme_selector", "root/button-" + button.id)) {
                            AvogadroGuiFactory.drawPaperButton(button.x, button.y, button.width, button.height,
                                    button.displayString, button.enabled, x, y);
                        }
            }
        }
    }

    private static final class Entries extends GuiSelectStringEntries {
        private final Selection selection;

        Entries(Selection screen, IConfigElement element, Map<Object, String> choices) {
            super(screen, Minecraft.getMinecraft(), element, choices);
            selection = screen;
            // Forge fixes selector rows at 11 pixels. Change this instance before it is displayed;
            // native scrolling, hit testing, selection and save handling then share the same row size.
            try {
                net.minecraftforge.fml.relauncher.ReflectionHelper.findField(net.minecraft.client.gui.GuiSlot.class,
                        "slotHeight", "field_148149_f").setInt(this, 50);
            } catch (IllegalAccessException failure) {
                throw new IllegalStateException("Cannot size the native theme list", failure);
            }
            setDimensions(screen.width, screen.height, 78, screen.height - 36);
            setShowSelectionBox(false);
            maxEntryWidth = getListWidth();
            listEntries.clear();
            choices.entrySet().forEach(entry -> listEntries.add(new Row(this, entry)));
        }

        @Override public int getListWidth() { return Math.max(120, Math.min(500, width - 40)); }

        @Override
        protected void elementClicked(int index, boolean doubleClick, int mouseX, int mouseY) {
            super.elementClicked(index, doubleClick, mouseX, mouseY);
            selection.applySelectedTheme();
        }

        @Override public void drawScreen(int x, int y, float partial) {
            super.drawScreen(x, y, partial);
            Gui.drawRect(left, top, right, top + 4, StructureStyle.PAPER());
            Gui.drawRect(left, bottom - 4, right, bottom, StructureStyle.PAPER());
            if (getMaxScroll() > 0) {
                int bar = getScrollBarX(), view = bottom - top;
                int thumb = Math.max(18, view * view / getContentHeight());
                int position = top + getAmountScrolled() * (view - thumb) / getMaxScroll();
                Gui.drawRect(bar, top, bar + 6, bottom, UiThemes.color("scrollbar.track", StructureStyle.SURFACE));
                Gui.drawRect(bar, position, bar + 6, position + thumb, UiThemes.color("scrollbar.thumb", StructureStyle.BORDER));
            }
        }

        @Override
        protected void drawContainerBackground(Tessellator tessellator) {
            Gui.drawRect(left, top, right, bottom, StructureStyle.PAPER());
        }

        @Override
        protected void overlayBackground(int start, int end, int startAlpha, int endAlpha) {
            Gui.drawRect(0, start, width, end, StructureStyle.PANEL());
        }

        @Override
        protected boolean drawTopBottomShadow(Tessellator tessellator) { return false; }
    }

    private static final class Row extends GuiSelectStringEntries.ListEntry {
        Row(Entries list, Map.Entry<Object, String> value) { super(list, value); }

        @Override
        public void drawEntry(int index, int x, int y, int width, int height, int mouseX, int mouseY,
                boolean selected, float partial) {
            try (var ignored = UiThemes.scope("theme_selector", "root/theme-" + value.getKey())) {
                boolean current = value.getKey().equals(owningList.owningScreen.currentValue);
                String state = current ? "selected." : UiThemes.hovered(mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height) ? "hover." : "";
                Gui.drawRect(x, y, x + width, y + height, UiThemes.color("button." + state + "border", StructureStyle.BORDER));
                Gui.drawRect(x + 1, y + 1, x + width - 1, y + height - 1,
                        UiThemes.color("button." + state + "background", StructureStyle.SURFACE));
                var font = PixelFont.renderer();
                int sampleWidth = Math.min(100, Math.max(56, width / 3)), textWidth = width - sampleWidth - 28;
                font.drawString(font.trimStringToWidth(value.getValue(), textWidth), x + 8, y + 8,
                        UiThemes.color("button." + state + "text", StructureStyle.INK));
                var entry = UiThemes.available().get(value.getKey().toString());
                if (entry != null) {
                    String description = entry.definition().description();
                    if (font.getStringWidth(description) > textWidth)
                        description = font.trimStringToWidth(description, Math.max(1, textWidth - font.getStringWidth("..."))) + "...";
                    font.drawString(description, x + 8, y + 25, StructureStyle.MUTED());
                    ThemeSwatch.draw(entry.compiled(), x + width - sampleWidth - 6, y + 4, sampleWidth, height - 8);
                }
            }
        }
    }
}

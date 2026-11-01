package io.github.wurtzitane.avogadro.client;

import io.github.wurtzitane.avogadro.Avogadro;
import io.github.wurtzitane.avogadro.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiControls;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.common.config.ConfigElement;
import net.minecraftforge.fml.client.config.DummyConfigElement.DummyCategoryElement;
import net.minecraftforge.fml.client.config.GuiConfigEntries;
import net.minecraftforge.fml.client.config.IConfigElement;
import net.minecraftforge.fml.client.IModGuiFactory;
import net.minecraftforge.fml.client.config.GuiConfig;
import net.minecraftforge.fml.client.config.GuiButtonExt;
import net.minecraftforge.fml.client.config.GuiCheckBox;
import net.minecraftforge.fml.client.config.HoverChecker;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * GUI factory providing customized configuration screens for the Avogadro mod.
 */
public final class AvogadroGuiFactory implements IModGuiFactory {

    /** Checks whether the current screen is an active {@link PaperConfig} panel. */
    static boolean isOpen() {
        return Minecraft.getMinecraft().currentScreen instanceof PaperConfig;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initialize(Minecraft minecraftInstance) {}

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasConfigGui() {
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public GuiScreen createConfigGui(GuiScreen parentScreen) {
        List<IConfigElement> categories = new ArrayList<>();
        for (String name : Config.CATEGORIES) {
            ConfigElement category = new ConfigElement(Config.configuration.getCategory(name)) {
                @Override
                public Class<? extends GuiConfigEntries.IConfigEntry> getConfigEntryClass() {
                    return PaperCategoryEntry.class;
                }
            };
            if (name.equals(Config.WORLD_HUD)) {
                List<IConfigElement> options = new ArrayList<>(category.getChildElements());
                String key = "worldHudKey";
                options.add(new DummyCategoryElement(key, "avogadro.configuration." + key, KeyBindingEntry.class));
                categories.add(new DummyCategoryElement(name, category.getLanguageKey(), options, PaperCategoryEntry.class));
            } else {
                categories.add(category);
            }
        }
        return new PaperConfig(parentScreen, categories, false, false, "Avogadro", null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<RuntimeOptionCategoryElement> runtimeGuiCategories() {
        return Set.of();
    }

    /**
     * Custom GUI configuration screen with themed paper styling.
     */
    private static final class PaperConfig extends GuiConfig {

        /** Constructs a new {@link PaperConfig} screen. */
        PaperConfig(GuiScreen parent, List<IConfigElement> elements, boolean worldRestart, boolean gameRestart,
                    String title, String subtitle) {
            super(parent, elements, Avogadro.MODID, worldRestart, gameRestart, title, subtitle);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void initGui() {
            if (entryList == null || needsRefresh) {
                entryList = new PaperEntries(this, Minecraft.getMinecraft());
                needsRefresh = false;
            }
            super.initGui();
            // Only drawing changes: Forge still owns saving, reset, undo and restart prompts.
            for (int i = 0; i < buttonList.size(); i++) {
                GuiButton original = buttonList.get(i);
                if (original.id < 2000 || original.id > 2002) {
                    continue;
                }
                PaperButton replacement = new PaperButton(original);
                buttonList.set(i, replacement);
                if (original == btnDefaultAll) {
                    btnDefaultAll = replacement;
                }
                if (original == btnUndoAll) {
                    btnUndoAll = replacement;
                }
            }
            int checkboxIndex = buttonList.indexOf(chkApplyGlobally);
            chkApplyGlobally = new PaperCheckBox(chkApplyGlobally);
            buttonList.set(checkboxIndex, chkApplyGlobally);
            checkBoxHoverChecker = new HoverChecker(chkApplyGlobally, 800);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void drawDefaultBackground() {
            Gui.drawRect(0, 0, width, height, StructureStyle.PANEL);
            Gui.drawRect(4, 4, width - 4, height - 4, StructureStyle.BORDER);
            Gui.drawRect(5, 5, width - 5, height - 5, StructureStyle.PANEL);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void drawCenteredString(FontRenderer font, String text, int x, int y, int color) {
            font = PixelFont.wrap(font);
            font.drawString(text, x - font.getStringWidth(text) / 2, y, StructureStyle.INK);
        }
    }

    /**
     * Custom configuration entries container with styled paper backgrounds and scrollbars.
     */
    private static final class PaperEntries extends GuiConfigEntries {
        private static final Field TEXT_FIELD = ReflectionHelper.findField(StringEntry.class, "textFieldValue");
        private static final Field VALID = ReflectionHelper.findField(ListEntryBase.class, "isValidValue");
        private static final Field VALUE_BUTTON = ReflectionHelper.findField(ButtonEntry.class, "btnValue");
        private static final Field UNDO_BUTTON = ReflectionHelper.findField(ListEntryBase.class, "btnUndoChanges");
        private static final Field RESET_BUTTON = ReflectionHelper.findField(ListEntryBase.class, "btnDefault");
        private static final Field FIELD_FONT = ReflectionHelper.findField(GuiTextField.class, "fontRenderer", "field_146211_a");

        /** Constructs a new {@link PaperEntries} instance. */
        PaperEntries(GuiConfig parent, Minecraft minecraft) {
            super(parent, minecraft);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected void drawContainerBackground(Tessellator tessellator) {
            Gui.drawRect(6, top, width - 6, bottom, StructureStyle.PAPER);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected void overlayBackground(int startY, int endY, int startAlpha, int endAlpha) {
            Gui.drawRect(6, startY, width - 6, endY, StructureStyle.PANEL);
            int boundary = startY == 0 ? endY : startY;
            Gui.drawRect(6, boundary, width - 6, boundary + 1, StructureStyle.RULE);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected boolean drawTopBottomShadow(Tessellator tessellator) {
            return false;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void drawScreen(int mouseX, int mouseY, float partial) {
            super.drawScreen(mouseX, mouseY, partial);
            // The native list draws its dark edge fades after overlayBackground.
            Gui.drawRect(left, top, right, top + 4, StructureStyle.PAPER);
            Gui.drawRect(left, bottom - 4, right, bottom, StructureStyle.PAPER);
            int scroll = getMaxScroll();
            if (scroll > 0) {
                int x = getScrollBarX(), view = bottom - top;
                int thumb = Math.max(32, Math.min(view - 8, view * view / getContentHeight()));
                int position = Math.max(top, top + (int) amountScrolled * (view - thumb) / scroll);
                Gui.drawRect(x, top, x + 6, bottom, StructureStyle.SURFACE);
                Gui.drawRect(x, position, x + 6, position + thumb, StructureStyle.BORDER);
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected void drawSlot(int index, int x, int y, int height, int mouseX, int mouseY, float partial) {
            super.drawSlot(index, x, y, height, mouseX, mouseY, partial);
            var entry = getListEntry(index);
            if (entry.getConfigElement().isProperty()) {
                Gui.drawRect(labelX - 1, y, controlX - 4, y + height, StructureStyle.PAPER);
                String key = entry.getConfigElement().getLanguageKey();
                String translated = I18n.format(key);
                String label = (entry.isChanged() ? TextFormatting.ITALIC.toString() : "")
                        + (translated.equals(key) ? entry.getName() : translated);
                label = PixelFont.renderer().trimStringToWidth(label, Math.max(1, controlX - labelX - 8));
                PixelFont.renderer().drawString(label, labelX, y + height / 2 - PixelFont.renderer().FONT_HEIGHT / 2,
                        !(boolean) read(VALID, entry) ? 0xA94436 : entry.enabled() ? StructureStyle.INK : StructureStyle.MUTED);
            }
            drawPaperButton((GuiButton) read(UNDO_BUTTON, entry), mouseX, mouseY);
            drawPaperButton((GuiButton) read(RESET_BUTTON, entry), mouseX, mouseY);
            if (entry instanceof StringEntry) {
                drawPaperField((GuiTextField) read(TEXT_FIELD, entry), (boolean) read(VALID, entry));
            } else if (entry instanceof ButtonEntry && !(entry instanceof NumberSliderEntry)) {
                var button = (GuiButton) read(VALUE_BUTTON, entry);
                String label = entry instanceof BooleanEntry value
                        ? I18n.format(value.getCurrentValue() ? "options.on" : "options.off") : button.displayString;
                drawPaperButton(button.x, button.y, button.width, button.height, label, button.enabled, mouseX, mouseY);
            }
        }

        /** Helper to read private fields via reflection. */
        private static Object read(Field field, Object owner) {
            try {
                return field.get(owner);
            } catch (IllegalAccessException failure) {
                throw new IllegalStateException("Cannot draw native configuration control", failure);
            }
        }

        /** Draws styled configuration text input fields. */
        private static void drawPaperField(GuiTextField field, boolean valid) {
            if (!field.getVisible()) {
                return;
            }
            try {
                FIELD_FONT.set(field, PixelFont.wrap((FontRenderer) FIELD_FONT.get(field)));
            } catch (IllegalAccessException failure) {
                throw new IllegalStateException("Cannot select configuration text font", failure);
            }
            int x = field.x, y = field.y, width = field.width;
            boolean background = field.getEnableBackgroundDrawing();
            Gui.drawRect(x - 1, y - 1, x + width + 1, y + field.height + 1,
                    !valid ? 0xFFA94436 : field.isFocused() ? 0xFF000000 | StructureStyle.ACCENT : 0xFFBEC9B9);
            Gui.drawRect(x, y, x + width, y + field.height, StructureStyle.PAPER);
            try {
                // Keep the real field's cursor, selection, scrolling, validation and input handling.
                field.width = field.getWidth();
                if (background) {
                    field.x += 4;
                    field.y += (field.height - 8) / 2;
                }
                field.setEnableBackgroundDrawing(false);
                field.setTextColor(valid ? StructureStyle.INK : 0xA94436);
                field.setDisabledTextColour(StructureStyle.MUTED);
                field.drawTextBox();
            } finally {
                field.x = x;
                field.y = y;
                field.width = width;
                field.setEnableBackgroundDrawing(background);
            }
        }
    }

    /**
     * Category entry renderer for sub-configuration panels.
     */
    public static class PaperCategoryEntry extends GuiConfigEntries.CategoryEntry {

        /** Constructs a new {@link PaperCategoryEntry}. */
        public PaperCategoryEntry(GuiConfig screen, GuiConfigEntries entries, IConfigElement element) {
            super(screen, entries, element);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected GuiScreen buildChildScreen() {
            return new PaperConfig(owningScreen, configElement.getChildElements(),
                    owningScreen.allRequireWorldRestart || configElement.requiresWorldRestart(),
                    owningScreen.allRequireMcRestart || configElement.requiresMcRestart(), owningScreen.title,
                    (owningScreen.titleLine2 == null ? "" : owningScreen.titleLine2) + " > " + name);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void drawEntry(int slotIndex, int x, int y, int width, int height, int mouseX, int mouseY,
                              boolean selected, float partial) {
            super.drawEntry(slotIndex, x, y, width, height, mouseX, mouseY, selected, partial);
            drawPaperButton(btnSelectCategory, mouseX, mouseY);
        }
    }

    /**
     * Themed push button using paper styling.
     */
    private static final class PaperButton extends GuiButtonExt {

        /** Constructs a new {@link PaperButton}. */
        PaperButton(GuiButton original) {
            super(original.id, original.x, original.y, original.width, original.height, original.displayString);
            enabled = original.enabled;
            visible = original.visible;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void drawButton(Minecraft minecraft, int mouseX, int mouseY, float partial) {
            drawPaperButton(this, mouseX, mouseY);
        }
    }

    /**
     * Themed checkbox control using paper styling.
     */
    private static final class PaperCheckBox extends GuiCheckBox {

        /** Constructs a new {@link PaperCheckBox}. */
        PaperCheckBox(GuiCheckBox original) {
            super(original.id, original.x, original.y, original.displayString, original.isChecked());
            width = original.width;
            height = original.height;
            enabled = original.enabled;
            visible = original.visible;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void drawButton(Minecraft minecraft, int mouseX, int mouseY, float partial) {
            if (!visible) {
                return;
            }
            hovered = enabled && mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
            int ink = enabled ? StructureStyle.INK : StructureStyle.MUTED;
            Gui.drawRect(x, y, x + 11, y + 11, hovered ? 0xFF000000 | StructureStyle.ACCENT : 0xFFBEC9B9);
            Gui.drawRect(x + 1, y + 1, x + 10, y + 10, StructureStyle.PAPER);
            if (isChecked()) {
                int tick = 0xFF000000 | ink;
                Gui.drawRect(x + 2, y + 5, x + 4, y + 7, tick);
                Gui.drawRect(x + 4, y + 6, x + 6, y + 8, tick);
                Gui.drawRect(x + 6, y + 3, x + 8, y + 6, tick);
            }
            PixelFont.renderer().drawString(displayString, x + 13, y + 2, ink);
        }
    }

    /** Helper method to draw a styled paper button from a {@link GuiButton}. */
    private static void drawPaperButton(GuiButton button, int mouseX, int mouseY) {
        if (!button.visible) {
            return;
        }
        drawPaperButton(button.x, button.y, button.width, button.height, button.displayString,
                button.enabled, mouseX, mouseY);
    }

    /** Helper method to draw a styled paper button given explicit bounds and label. */
    private static void drawPaperButton(int x, int y, int width, int height, String label, boolean enabled,
                                        int mouseX, int mouseY) {
        boolean hover = enabled && mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        Gui.drawRect(x, y, x + width, y + height,
                hover ? 0xFF000000 | StructureStyle.ACCENT : 0xFFBEC9B9);
        Gui.drawRect(x + 1, y + 1, x + width - 1, y + height - 1,
                hover ? 0xFFFDFCF7 : StructureStyle.SURFACE);
        var font = PixelFont.renderer();
        String text = font.trimStringToWidth(label, width - 8);
        font.drawString(text, x + (width - font.getStringWidth(text)) / 2,
                y + (height - font.FONT_HEIGHT) / 2 + 1, enabled ? StructureStyle.INK : StructureStyle.MUTED);
    }

    /**
     * Configuration entry for accessing key binding controls.
     */
    public static final class KeyBindingEntry extends PaperCategoryEntry {

        /** Constructs a new {@link KeyBindingEntry}. */
        public KeyBindingEntry(GuiConfig screen, GuiConfigEntries entries, IConfigElement element) {
            super(screen, entries, element);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected GuiScreen buildChildScreen() {
            return new GuiControls(owningScreen, Minecraft.getMinecraft().gameSettings);
        }
    }
}
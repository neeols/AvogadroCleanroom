package io.github.wurtzitane.avogadro.client;

import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.widget.Widget;
import io.github.wurtzitane.avogadro.Config;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.resources.I18n;
import net.minecraftforge.common.config.Property;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The examiner's compact settings page; each user action saves once.
 */
final class AtomViewSettings {

    /** Array of configurable toggle setting keys. */
    private static final String[] TOGGLES = {"atomCloud", "atomOrbits", "atomNucleus", "atomElectrons", "atomDetails"};

    /** Private constructor to prevent instantiation of utility class. */
    private AtomViewSettings() {}

    /**
     * Adds the atom view settings panel components to a ModularPanel.
     *
     * @param panel    the modular panel container
     * @param top      top coordinate offset
     * @param width    total panel width
     * @param height   total panel height
     * @param visible  supplier checking panel visibility
     * @param keepView runnable action to preserve the view state
     */
    static void addTo(ModularPanel panel, int top, int width, int height,
                      BooleanSupplier visible, Runnable keepView) {
        panel.child(new Widget<>().pos(10, top).size(width - 20, height)
                .setEnabledIf(widget -> visible.getAsBoolean())
                .background((context, x, y, w, h, theme) -> {
                    keepView.run();
                    Gui.drawRect(x, y, x + w, y + h, StructureStyle.PAPER);
                    StructureStyle.text(I18n.format("avogadro.examiner.settings.heading"), x + 8, y + 8, w - 16, StructureStyle.INK);
                }));
        int columnWidth = (width - 40) / 2;
        int pitch = Math.min(30, Math.max(14, (height - 26) / 6));
        int buttonHeight = Math.min(20, pitch - 2);
        for (int index = 0; index < TOGGLES.length; index++) {
            String key = TOGGLES[index];
            add(panel, top, columnWidth, pitch, buttonHeight, index, visible,
                    () -> I18n.format("avogadro.examiner.setting." + key) + ": " +
                            I18n.format(property(key).getBoolean() ? "options.on" : "options.off"),
                    () -> {
                        property(key).set(!property(key).getBoolean());
                        Config.sync();
                    });
        }
        add(panel, top, columnWidth, pitch, buttonHeight, 5, visible,
                () -> I18n.format("avogadro.examiner.setting.text", Math.round(Config.ATOM_TEXT_SCALE.get() * 100)),
                () -> {
                    double scale = Config.ATOM_TEXT_SCALE.get();
                    property("atomTextScale").set(scale < 1 ? 1 : scale < 1.25 ? 1.25 : scale < 1.5 ? 1.5 : .75);
                    Config.sync();
                });
        add(panel, top, columnWidth, pitch, buttonHeight, 6, visible,
                () -> I18n.format("avogadro.examiner.setting.size", Config.ATOM_WIDTH.get(), Config.ATOM_HEIGHT.get()),
                () -> {
                    int widthNow = Config.ATOM_WIDTH.get();
                    property("atomWidth").set(widthNow < 1200 ? 1200 : widthNow < 1600 ? 1600 : 1000);
                    property("atomHeight").set(widthNow < 1200 ? 720 : widthNow < 1600 ? 900 : 640);
                    Config.sync();
                });
        add(panel, top, columnWidth, pitch, buttonHeight, 7, visible,
                () -> I18n.format("avogadro.examiner.setting.fps", Config.ATOM_FPS.get()),
                () -> {
                    int next = 24;
                    for (int preset : new int[]{24, 30, 50, 60, 120, 240, 360, 1000}) {
                        if (preset > Config.ATOM_FPS.get()) {
                            next = preset;
                            break;
                        }
                    }
                    property("atomFps").set(next);
                    Config.sync();
                });
        add(panel, top, columnWidth, pitch, buttonHeight, 8, visible,
                () -> I18n.format("avogadro.examiner.setting.turn", Config.SPIN_SECONDS.get()),
                () -> {
                    Config.configuration.getCategory(Config.APPEARANCE).get("spinSeconds")
                            .set(Config.SPIN_SECONDS.get() < 6 ? 6 : Config.SPIN_SECONDS.get() < 12 ? 12 : 3);
                    Config.sync();
                });
        add(panel, top, columnWidth, pitch, buttonHeight, 9, visible,
                () -> I18n.format("avogadro.examiner.setting.viewerFps", Config.VIEWER_FPS.get() == 0
                        ? I18n.format("avogadro.examiner.setting.unlimited") : Config.VIEWER_FPS.get()),
                () -> {
                    int fps = Config.VIEWER_FPS.get();
                    property("viewerFps").set(fps == 0 ? 60 : fps < 120 ? 120 : fps < 240 ? 240 : 0);
                    Config.sync();
                });
        add(panel, top, columnWidth, pitch, buttonHeight, 10, visible,
                () -> I18n.format("avogadro.examiner.setting.restore"), AtomViewSettings::restore);
        add(panel, top, columnWidth, pitch, buttonHeight, 11, visible,
                () -> I18n.format("avogadro.examiner.setting.predictNeutralElectronConfigs") + ": " +
                        I18n.format(Config.PREDICT_NEUTRAL_ELECTRON_CONFIGS.get() ? "options.on" : "options.off"),
                () -> {
                    property("predictNeutralElectronConfigs").set(!Config.PREDICT_NEUTRAL_ELECTRON_CONFIGS.get());
                    Config.sync();
                });
    }

    /** Helper method to add a configured setting button to the panel. */
    private static void add(ModularPanel panel, int top, int width, int pitch, int height, int index,
                            BooleanSupplier visible, Supplier<String> label, Runnable action) {
        panel.child(StructureStyle.button(label, action)
                .pos(16 + index % 2 * (width + 8), top + 26 + index / 2 * pitch).size(width, height)
                .setEnabledIf(widget -> visible.getAsBoolean()));
    }

    /** Retrieves a configuration property by its key. */
    private static Property property(String key) {
        return Config.configuration.getCategory(Config.ATOMS).get(key);
    }

    /** Restores all atom view settings back to their default values. */
    private static void restore() {
        for (String key : TOGGLES) {
            property(key).set(true);
        }
        property("atomWidth").set(1000);
        property("atomHeight").set(640);
        property("atomFps").set(24);
        property("viewerFps").set(0);
        property("atomTextScale").set(1.0);
        property("predictNeutralElectronConfigs").set(false);
        Config.sync();
    }
}
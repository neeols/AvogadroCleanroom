package io.github.wurtzitane.avogadro.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.resources.I18n;

import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.widget.Widget;
import io.github.wurtzitane.avogadro.api.CrystalCell;
import io.github.wurtzitane.avogadro.api.CrystalGeometry;
import io.github.wurtzitane.avogadro.item.MoleculeItems;
import io.github.wurtzitane.avogadro.molecule.MoleculeDef;
import org.lwjgl.input.Keyboard;

import static io.github.wurtzitane.avogadro.client.StructureStyle.*;

/**
 * Client-only crystal examiner screen controller; chemistry and cell data belong to the definition's owner.
 */
public final class CrystalExaminer {

    /** Private constructor to prevent instantiation of utility class. */
    private CrystalExaminer() {}

    /**
     * Opens the crystal examiner screen for the specified molecule definition.
     *
     * @param definition molecule definition
     */
    public static void open(MoleculeDef definition) {
        open(definition, null);
    }

    /**
     * Opens the crystal examiner screen with an optional return callback.
     *
     * @param definition molecule definition
     * @param onReturn   runnable action executed on return
     */
    public static void open(MoleculeDef definition, Runnable onReturn) {
        Minecraft client = Minecraft.getMinecraft();
        ViewerWindows.schedule(() -> {
            if (client.player == null) {
                return;
            }
            ViewerWindows.prepareOpen();
            ScaledResolution resolution = ViewerWindows.resolution();
            ViewerWindows.open(createScreen(definition, Math.min(430, resolution.getScaledWidth() - 20),
                    Math.min(300, resolution.getScaledHeight() - 20), onReturn));
        });
    }

    /**
     * Creates a crystal examiner modular screen without a return callback.
     *
     * @param definition molecule definition
     * @param width      screen width in pixels
     * @param height     screen height in pixels
     * @return a new {@link ModularScreen} instance
     */
    static ModularScreen createScreen(MoleculeDef definition, int width, int height) {
        return createScreen(definition, width, height, null);
    }

    /** Creates a crystal examiner modular screen with an optional return callback. */
    private static ModularScreen createScreen(MoleculeDef definition, int width, int height, Runnable onReturn) {
        CrystalCell cell = CrystalCell.of(definition);
        if (cell == null) {
            throw new IllegalArgumentException("Crystal cell is required");
        }
        int windowActionWidth = ViewerWindows.available() ? 24 : 0;
        int sideWidth = Math.min(150, width * 2 / 5);
        int viewWidth = width - sideWidth - 26;
        int viewHeight = height - 98;
        CrystalViewport view = new CrystalViewport(definition, cell, viewWidth, viewHeight);
        ModularPanel panel = ModularPanel.defaultPanel("crystal_examiner", width, height)
                .background(surface(PANEL, BORDER));
        panel.child(new Widget<>().size(width, 38).background((context, x, y, w, h, theme) ->
                header(MoleculeItems.displayName(definition), "",
                        x, y, w, (onReturn == null ? 108 : 156) + windowActionWidth)));
        panel.child(ExaminerPresentation.controls(false, MUTED).pos(width - 44 - windowActionWidth, 9));
        panel.child(view.pos(10, 40).size(viewWidth, viewHeight));
        panel.child(new CrystalDetails(definition, cell, view).pos(width - sideWidth - 10, 40)
                .size(sideWidth, height - 50));

        int gap = 6;
        for (int count = 1; count <= CrystalGeometry.MAX_CELLS_PER_AXIS; count++) {
            int selected = count;
            int left = (count - 1) * (viewWidth + gap) / CrystalGeometry.MAX_CELLS_PER_AXIS;
            int right = count * (viewWidth + gap) / CrystalGeometry.MAX_CELLS_PER_AXIS - gap;

            panel.child(button(
                    () -> viewWidth < 300 ? Integer.toString(selected) : sizeLabel(selected),
                    () -> view.requestCount(selected), () -> view.count == selected, selected >= 4)
                    .pos(10 + left, height - 52).size(right - left, 18)
                    .tooltip(tooltip -> tooltip.addLine(I18n.format("avogadro.crystal.cells", selected, selected, selected))
                            .addLine(I18n.format("avogadro.crystal.select_size"))));
        }
        int actionWidth = (viewWidth - 18) / 4;
        panel.child(button(() -> I18n.format("avogadro.crystal.edges"), view::toggleOutlines,
                () -> view.outlines, false).pos(10, height - 28).size(actionWidth, 18));
        panel.child(button(() -> I18n.format("avogadro.crystal.local_bonds"), view::toggleBonds,
                () -> view.bonds, false).pos(16 + actionWidth, height - 28).size(actionWidth, 18));
        panel.child(button(() -> I18n.format(view.paused ? "avogadro.examiner.resume" : "avogadro.examiner.pause"),
                view::pause, () -> view.paused, false)
                .pos(22 + 2 * actionWidth, height - 28).size(actionWidth, 18));
        panel.child(button(() -> I18n.format("avogadro.crystal.reset"), view::resetView)
                .pos(28 + 3 * actionWidth, height - 28).size(viewWidth - 3 * actionWidth - 18, 18));

        panel.child(button(() -> I18n.format("avogadro.crystal.load"), view::confirmCount)
                .pos(20, 40 + viewHeight - 28).size((viewWidth - 26) / 2, 18)
                .setEnabledIf(widget -> view.pendingCount != 0));
        panel.child(button(() -> I18n.format("gui.cancel"), view::cancelCount)
                .pos(26 + (viewWidth - 26) / 2, 40 + viewHeight - 28).size((viewWidth - 26) / 2, 18)
                .setEnabledIf(widget -> view.pendingCount != 0));
        boolean[] returnToParent = { true };
        ModularScreen screen = new ModularScreen("avogadro", panel) {
            private boolean returned;
            @Override
            public boolean onKeyPressed(char character, int key) {
                if (ViewerWindows.isCloseKey(this, key)) {
                    if (view.pendingCount != 0) {
                        view.cancelCount();
                    } else {
                        ViewerWindows.close(this);
                    }
                    return true;
                }
                if (AvogadroKeys.PAUSE_MOLECULE.isActiveAndMatches(key)) {
                    if (!Keyboard.isRepeatEvent()) {
                        view.pause();
                    }
                    return true;
                }
                return super.onKeyPressed(character, key);
            }

            @Override
            public void onClose() {
                view.release();
                view.cancelPreparation();
                super.onClose();
                Minecraft client = Minecraft.getMinecraft();
                if (!ViewerWindows.isHosted(this) && !returned && returnToParent[0] && onReturn != null && client.world != null && client.player != null) {
                    returned = true;
                    client.addScheduledTask(onReturn);
                }
            }
        }.openParentOnClose(false).pausesGame(false);
        panel.child(button(() -> I18n.format("avogadro.crystal.atoms"), () -> {
            returnToParent[0] = false;
            MoleculeExaminer.openAtoms(definition, () -> open(definition, onReturn));
        }).pos(width - 100 - windowActionWidth, 8).size(50, 20));
        if (onReturn != null) {
            panel.child(button(() -> I18n.format("avogadro.examiner.back"), () -> ViewerWindows.close(screen))
                    .pos(width - 148 - windowActionWidth, 8).size(42, 20));
        }
        panel.child(closeButton(() -> ViewerWindows.close(screen)).pos(width - 24, 8));
        ViewerWindows.addButton(panel, () -> open(definition), width - 46, 9);
        return screen;
    }

    /** Formats a crystal cell axis dimension label. */
    static String sizeLabel(int count) {
        return count + " x " + count + " x " + count;
    }
}
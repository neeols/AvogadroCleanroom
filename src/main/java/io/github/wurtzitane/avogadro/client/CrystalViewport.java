package io.github.wurtzitane.avogadro.client;

import java.util.List;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;

import io.github.wurtzitane.avogadro.api.CrystalCell;
import io.github.wurtzitane.avogadro.api.CrystalGeometry;
import io.github.wurtzitane.avogadro.molecule.MoleculeDef;
import io.github.wurtzitane.avogadro.molecule.MoleculeStore;
import io.github.wurtzitane.avogadro.molecule.MoleculeStructure;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.resources.I18n;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;

/**
 * Camera and finite lattice state widget, rebuilt only when the repeat count or bond setting changes.
 */
final class CrystalViewport extends Widget<CrystalViewport> implements Interactable {

    /** Molecule camera for handling view transformations. */
    final MoleculeCamera camera;
    private final MoleculeDef definition;
    private final CrystalCell cell;
    private final boolean registeredDefinition;
    private final MoleculeExaminer.AnimationClock clock = new MoleculeExaminer.AnimationClock(System.nanoTime());
    private final ExaminerPresentation.ZoomFeedback zoomFeedback = new ExaminerPresentation.ZoomFeedback();
    private final PreviewPreparation<CrystalPreviewCache.Prepared> preparation = new PreviewPreparation<>();
    MoleculeStructure geometry;
    private CrystalOutline outline;
    int count = 1, pendingCount;
    boolean bonds, outlines = true, paused;
    private float fit = 1;
    private String status;
    private long definitions = MoleculeStore.revision();
    private boolean definitionChanged;

    /**
     * Creates a {@link CrystalViewport}.
     *
     * @param definition molecule definition
     * @param cell crystal cell configuration
     * @param width viewport width in pixels
     * @param height viewport height in pixels
     */
    CrystalViewport(MoleculeDef definition, CrystalCell cell, int width, int height) {
        this.definition = definition;
        this.cell = cell;
        registeredDefinition = MoleculeStore.get(definition.id()) == definition;
        camera = new MoleculeCamera(width, height);
        rebuild();
    }

    /** Rebuilds or requests preview preparation based on current cache states. */
    private void rebuild() {
        if (!definitionCurrent()) {
            return;
        }
        int selectedCount = count;
        boolean selectedBonds = bonds;
        preparation.close();
        var cached = CrystalPreviewCache.find(definition.structure(), cell, selectedCount, selectedBonds);
        if (cached != null) {
            apply(cached);
        } else {
            preparation.request(
                    () -> CrystalPreviewCache.prepare(definition.structure(), cell, selectedCount, selectedBonds));
        }
        status = null;
    }

    /** Validates whether the definition revision is current. */
    private boolean definitionCurrent() {
        long revision = MoleculeStore.revision();
        if (!definitionChanged && registeredDefinition && definitions != revision
                && MoleculeStore.get(definition.id()) != definition) {
            definitionChanged = true;
            pendingCount = 0;
            preparation.close();
            status = I18n.format("avogadro.preview.definition_changed");
        }
        definitions = revision;
        return !definitionChanged;
    }

    /** Applies prepared preview geometry, outline, and fit factors. */
    private void apply(CrystalPreviewCache.Prepared ready) {
        geometry = ready.geometry();
        outline = ready.outline();
        fit = ready.fit();
    }

    /** Requests a specific crystal cell repetition count. */
    void requestCount(int requested) {
        if (!definitionCurrent()) {
            return;
        }
        if (requested < 1 || requested > CrystalGeometry.MAX_CELLS_PER_AXIS) {
            throw new IllegalArgumentException("Invalid crystal preview size");
        }
        pendingCount = 0;
        status = null;
        if ((long) definition.structure().atoms().size() * requested * requested
                * requested > CrystalGeometry.MAX_ATOMS) {
            status = I18n.format("avogadro.crystal.atom_limit", CrystalGeometry.MAX_ATOMS);
        } else if (requested == count) {
            return;
        } else if (requested >= 4) {
            release();
            pendingCount = requested;
        } else {
            selectCount(requested);
        }
    }

    /** Confirms the pending repetition count selection. */
    void confirmCount() {
        if (pendingCount != 0) {
            selectCount(pendingCount);
        }
    }

    /** Cancels a pending repetition count selection. */
    void cancelCount() {
        pendingCount = 0;
    }

    /** Selects and applies a new repetition count. */
    private void selectCount(int selected) {
        release();
        count = selected;
        pendingCount = 0;
        rebuild();
    }

    /** Resets the viewport camera and zoom feedback. */
    void resetView() {
        camera.reset();
        zoomFeedback.clear();
    }

    /** Toggles local bond rendering mode. */
    void toggleBonds() {
        if (!definitionCurrent()) {
            return;
        }
        bonds = !bonds;
        rebuild();
    }

    /** Toggles crystal cell outline edge rendering. */
    void toggleOutlines() {
        outlines = !outlines;
    }

    /** Toggles animation pausing. */
    void pause() {
        clock.toggle(System.nanoTime());
        paused = !paused;
    }

    /** Returns the current animation time clock value. */
    private long now() {
        return clock.time(System.nanoTime());
    }

    /** Calculates automatic yaw rotation angle. */
    private float automaticYaw() {
        return MoleculeRenderer.rotationAngle(now() / 1_000_000_000.0);
    }

    /** Releases active mouse dragging states. */
    void release() {
        if (camera.isDragging()) {
            camera.release(camera.draggingButton(), automaticYaw(), now());
        }
    }

    /** Cancels ongoing preview preparation tasks. */
    void cancelPreparation() {
        preparation.close();
    }

    /** Checks whether preview preparation is currently pending. */
    boolean loading() {
        return preparation.isPending();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void dispose() {
        cancelPreparation();
        super.dispose();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void draw(ModularGuiContext context, WidgetThemeEntry<?> theme) {
        definitionCurrent();
        var ready = preparation.take();
        if (ready != null) {
            apply(ready);
        }
        int width = getArea().width, height = getArea().height;
        Gui.drawRect(0, 0, width, height, UiThemes.color("viewport.border", StructureStyle.VIEW_BORDER));
        Gui.drawRect(1, 1, width - 1, height - 1, UiThemes.color("viewport.background", StructureStyle.PAPER));
        camera.rendered();
        if (camera.isDragging()) {
            if (Mouse.isButtonDown(camera.draggingButton()) && Display.isActive()) {
                camera.drag(context.getMouseX(), context.getMouseY(), camera.draggingButton());
            } else {
                release();
            }
        }
        long time = now();
        int imageSize = Math.max(1, (int) (Math.min(width - 16, height - 16) * fit));
        if (geometry != null) {
            MoleculeRenderer.renderCrystal(geometry, 1, 1, width - 2, height - 2, imageSize,
                    camera.yaw(automaticYaw(), time), camera.pitch(time), camera.panX(), camera.panY(), camera.zoom(),
                    outlines ? outline : null);
        }
        zoomFeedback.draw(camera.zoom(), width, height, StructureStyle.MUTED());
        if (pendingCount != 0) {
            drawConfirmation(width, height);
        } else if (status != null) {
            drawNotice(status, width, height);
        } else if (loading() || preparation.failed()) {
            StructureStyle.text(
                    I18n.format(preparation.failed() ? "avogadro.preview.failed" : "avogadro.preview.preparing"), 8,
                    height - 16, width - 16, StructureStyle.MUTED());
        }
    }

    /** Draws the large size confirmation warning overlay. */
    private void drawConfirmation(int width, int height) {
        var font = PixelFont.renderer();
        String totals = I18n.format("avogadro.crystal.large_size", CrystalExaminer.sizeLabel(pendingCount),
                pendingCount * pendingCount * pendingCount,
                (long) definition.structure().atoms().size() * pendingCount * pendingCount * pendingCount);
        List<String> lines = font.listFormattedStringToWidth(totals, width - 22);
        int top = Math.max(1, height - 56 - lines.size() * 11);
        Gui.drawRect(1, 1, width - 1, height - 1, UiThemes.color("crystal.notice.background", 0xC0F4F1E8));
        Gui.drawRect(1, top, width - 1, height - 1, StructureStyle.NOTICE());
        Gui.drawRect(1, top, width - 1, top + 1, StructureStyle.NOTICE_BORDER());
        StructureStyle.text(I18n.format("avogadro.crystal.large_preview"), 10, top + 8, width - 20,
                StructureStyle.WARNING());
        for (int i = 0; i < lines.size(); i++) {
            StructureStyle.text(lines.get(i), 10, top + 23 + i * 11, width - 20, StructureStyle.INK());
        }
    }

    /** Draws a notification message overlay. */
    private void drawNotice(String message, int width, int height) {
        var font = PixelFont.renderer();
        List<String> lines = font.listFormattedStringToWidth(message, width - 20);
        int top = height - 9 - lines.size() * 10;
        Gui.drawRect(1, top - 5, width - 1, height - 1, StructureStyle.NOTICE());
        for (int i = 0; i < lines.size(); i++) {
            StructureStyle.text(lines.get(i), 9, top + i * 10, width - 18, StructureStyle.WARNING());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Result onMousePressed(int button) {
        if (pendingCount != 0) {
            return Result.IGNORE;
        }
        if (button == 2) {
            resetView();
            return Result.SUCCESS;
        }
        return camera.start(getContext().getMouseX(), getContext().getMouseY(), button, automaticYaw(), now())
                ? Result.SUCCESS
                : Result.IGNORE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onMouseDrag(int button, long elapsed) {
        camera.drag(getContext().getMouseX(), getContext().getMouseY(), button);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean onMouseRelease(int button) {
        return camera.release(button, automaticYaw(), now());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean onMouseScroll(UpOrDown direction, int amount) {
        if (pendingCount != 0) {
            return true;
        }
        float previousZoom = camera.zoom();
        boolean handled = camera.scroll(direction.modifier, getContext().getMouseX() - getArea().width / 2f,
                getContext().getMouseY() - getArea().height / 2f);
        if (camera.zoom() != previousZoom) {
            zoomFeedback.changed(System.nanoTime());
        }
        return handled;
    }
}

package io.github.wurtzitane.avogadro.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.resources.I18n;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;
import io.github.wurtzitane.avogadro.api.ChemicalFormula;
import io.github.wurtzitane.avogadro.api.MoleculeWeight;
import io.github.wurtzitane.avogadro.api.PolymerDefinition;
import io.github.wurtzitane.avogadro.api.PolymerGeometry;
import io.github.wurtzitane.avogadro.api.StructureFragment;
import io.github.wurtzitane.avogadro.item.MoleculeItems;
import io.github.wurtzitane.avogadro.molecule.MoleculeDef;
import io.github.wurtzitane.avogadro.molecule.MoleculeStructure;
import io.github.wurtzitane.avogadro.molecule.MoleculeStore;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;

import static io.github.wurtzitane.avogadro.client.StructureStyle.*;

/** A finite, end-capped preview of an authored repeat unit; no bulk chain length is assumed. */
public final class PolymerExaminer {

    /** Private constructor to prevent instantiation of utility class. */
    private PolymerExaminer() {}

    /**
     * Opens the polymer examiner screen for a given molecule definition.
     *
     * @param definition molecule definition
     */
    public static void open(MoleculeDef definition) {
        Minecraft client = Minecraft.getMinecraft();
        ViewerWindows.schedule(() -> {
            if (client.player == null) return;
            ViewerWindows.prepareOpen();
            ScaledResolution resolution = ViewerWindows.resolution();
            ViewerWindows.open(createScreen(definition, Math.min(430, resolution.getScaledWidth() - 20),
                    Math.min(300, resolution.getScaledHeight() - 20)));
        });
    }

    /**
     * Creates and configures the polymer examiner modular screen.
     *
     * @param definition molecule definition
     * @param width      screen width
     * @param height     screen height
     * @return constructed {@link ModularScreen} instance
     */
    static ModularScreen createScreen(MoleculeDef definition, int width, int height) {
        PolymerDefinition polymer = PolymerDefinition.of(definition);
        if (polymer == null) throw new IllegalArgumentException("A polymer repeat unit is required");
        int windowActionWidth = ViewerWindows.available() ? 24 : 0;
        int sideWidth = Math.min(150, width * 2 / 5), viewWidth = width - sideWidth - 26;
        ChainViewport view = new ChainViewport(definition, polymer, viewWidth, height - 74);
        ModularPanel panel = ModularPanel.defaultPanel("polymer_examiner", width, height)
                .background(surface(PANEL, BORDER));
        panel.child(new Widget<>().size(width, 38).background((context, x, y, w, h, theme) ->
                header(MoleculeItems.displayName(definition), "", x, y, w, 60 + windowActionWidth)));
        panel.child(ExaminerPresentation.controls(false, MUTED).pos(width - 44 - windowActionWidth, 9));
        panel.child(view.pos(10, 40).size(viewWidth, height - 74));
        int sideLeft = width - sideWidth - 10;
        panel.child(new Widget<>().pos(sideLeft, 40).size(sideWidth, height - 50)
                .background((context, x, y, w, h, theme) -> Gui.drawRect(x, y, x + w, y + h, PAPER)));
        panel.child(new ChainDetails(polymer, view).pos(sideLeft, 40).size(sideWidth, height - 96));
        panel.child(new Widget<>().pos(sideLeft + 10, height - 48).size(sideWidth - 20, 12)
                .background((context, x, y, w, h, theme) ->
                        text(I18n.format("avogadro.polymer.units", view.units, view.maxUnits), x, y, w, INK)));
        int buttonWidth = (sideWidth - 38) / 4;
        int[] steps = { -8, -1, 1, 8 };
        for (int index = 0; index < steps.length; index++) {
            int delta = steps[index];
            panel.child(button(() -> delta > 0 ? "+" + delta : Integer.toString(delta), () -> view.changeUnits(delta))
                    .pos(sideLeft + 10 + index * (buttonWidth + 6), height - 28).size(buttonWidth, 18)
                    .tooltip(tooltip -> tooltip.addLine(I18n.format("avogadro.polymer.chain_length"))));
        }
        panel.child(button(() -> I18n.format(view.paused ? "avogadro.examiner.resume" : "avogadro.examiner.pause"),
                view::pause, () -> view.paused, false).pos(10, height - 28).size((viewWidth - 6) / 2, 18));
        panel.child(button(() -> I18n.format("avogadro.examiner.reset"), view::resetView)
                .pos(16 + (viewWidth - 6) / 2, height - 28).size((viewWidth - 6) / 2, 18));
        ModularScreen screen = new ModularScreen("avogadro", panel) {
            @Override
            public boolean onKeyPressed(char character, int key) {
                if (ViewerWindows.isCloseKey(this, key)) {
                    ViewerWindows.close(this);
                    return true;
                }
                if (AvogadroKeys.PAUSE_MOLECULE.isActiveAndMatches(key)) {
                    if (!Keyboard.isRepeatEvent()) view.pause();
                    return true;
                }
                return super.onKeyPressed(character, key);
            }

            @Override
            public void onClose() {
                view.release();
                view.preparation.close();
                super.onClose();
            }
        }.openParentOnClose(false).pausesGame(false);
        panel.child(closeButton(() -> ViewerWindows.close(screen)).pos(width - 24, 8));
        ViewerWindows.addButton(panel, () -> open(definition), width - 46, 9);
        return screen;
    }

    /**
     * Details sidebar widget displaying polymer formula, repeat units, mass, and end groups.
     */
    private static final class ChainDetails extends StructureProperties<ChainDetails> {
        private final PolymerDefinition polymer;
        private final ChainViewport view;

        /**
         * Constructs a new chain details view.
         *
         * @param polymer polymer definition
         * @param view    chain viewport instance
         */
        ChainDetails(PolymerDefinition polymer, ChainViewport view) {
            this.polymer = polymer;
            this.view = view;
        }

        /**
         * Computes the content revision hash.
         *
         * @return revision long value
         */
        @Override
        protected long contentRevision() {
            return view.revision * 4 + (view.preparation.isPending() ? 1 : 0) + (view.preparation.failed() ? 2 : 0);
        }

        /**
         * Builds the detail rows for the polymer chain.
         *
         * @param rows rows builder instance
         */
        @Override
        protected void build(Rows rows) {
            rows.heading(I18n.format("avogadro.polymer.repeat_unit"));
            rows.paragraph(ChemicalFormula.display(polymer.formula()), INK);
            rows.rule();
            rows.heading(I18n.format("avogadro.polymer.finite_preview"));
            if (view.definitionChanged && view.geometry == null) return;
            if (view.preparation.isPending() || view.geometry == null) {
                rows.paragraph(I18n.format(view.preparation.failed() ? "avogadro.preview.failed" :
                        "avogadro.preview.preparing"), MUTED);
                return;
            }
            rows.paragraph(view.formula, INK);
            rows.y += 4;
            rows.paragraph(view.mass, MUTED);
            rows.paragraph(I18n.format("avogadro.polymer.atoms", view.geometry.atoms().size()), MUTED);
            rows.y += 8;
            rows.paragraph(I18n.format("avogadro.polymer.end_groups"), MUTED);
        }
    }

    /**
     * Viewport widget rendering 3D polymer chains and handling mouse interactions.
     */
    private static final class ChainViewport extends Widget<ChainViewport> implements Interactable {
        private final PolymerDefinition polymer;
        private final MoleculeDef definition;
        private final boolean registeredDefinition;
        private final MoleculeCamera camera;
        private final MoleculeExaminer.AnimationClock clock = new MoleculeExaminer.AnimationClock(System.nanoTime());
        private final ExaminerPresentation.ZoomFeedback zoomFeedback = new ExaminerPresentation.ZoomFeedback();
        private final PreviewPreparation<Prepared> preparation = new PreviewPreparation<>();
        private record Prepared(MoleculeStructure geometry, String formula, String mass) {}
        private MoleculeStructure geometry;
        private final int maxUnits;
        private int units;
        private String formula, mass;
        private boolean paused;
        private long revision, resources = MoleculeCategoryStyle.reloadRevision();
        private long definitions = MoleculeStore.revision();
        private boolean definitionChanged;

        /**
         * Constructs a new chain viewport.
         *
         * @param definition molecule definition
         * @param polymer    polymer definition
         * @param width      viewport width
         * @param height     viewport height
         */
        ChainViewport(MoleculeDef definition, PolymerDefinition polymer, int width, int height) {
            this.definition = definition;
            this.polymer = polymer;
            registeredDefinition = MoleculeStore.get(definition.id()) == definition;
            camera = new MoleculeCamera(width, height);
            int caps = polymer.headCap().structure().atoms().size() + polymer.tailCap().structure().atoms().size();
            int capBonds = polymer.headCap().structure().bonds().size() + polymer.tailCap().structure().bonds().size();
            int atomLimit = (PolymerGeometry.MAX_ATOMS - caps) / polymer.repeatUnit().atoms().size();
            int bondLimit = (StructureFragment.MAX_BONDS - capBonds - 1) / (polymer.repeatUnit().bonds().size() + 1);
            maxUnits = Math.min(PolymerGeometry.MAX_UNITS, Math.min(atomLimit, bondLimit));
            units = polymer.previewUnits();
            rebuild();
        }

        /** Rebuilds or requests async preparation of the polymer chain structure. */
        private void rebuild() {
            if (!definitionCurrent()) return;
            int selectedUnits = units;
            preparation.request(() -> {
                MoleculeStructure geometry = PolymerGeometry.chain(polymer, selectedUnits);
                return new Prepared(geometry, ChemicalFormula.display(polymer.chainFormula(selectedUnits)),
                        MoleculeWeight.calculate(geometry).molarMassText());
            });
            revision++;
        }

        /**
         * Checks whether the molecule definition is current and unchanged.
         *
         * @return {@code true} if current, {@code false} otherwise
         */
        private boolean definitionCurrent() {
            long current = MoleculeStore.revision();
            if (!definitionChanged && registeredDefinition && definitions != current &&
                    MoleculeStore.get(definition.id()) != definition) {
                definitionChanged = true;
                preparation.close();
                revision++;
            }
            definitions = current;
            return !definitionChanged;
        }

        /**
         * Changes the polymer repeat unit count by a specified delta.
         *
         * @param delta repeat unit count delta
         */
        void changeUnits(int delta) {
            if (!definitionCurrent()) return;
            int selected = Math.clamp(units + delta, 1, maxUnits);
            if (selected == units) return;
            release();
            units = selected;
            rebuild();
        }

        /** Resets camera position and zoom feedback. */
        void resetView() {
            camera.reset();
            zoomFeedback.clear();
        }

        /** Toggles animation pause state. */
        void pause() {
            clock.toggle(System.nanoTime());
            paused = !paused;
        }

        /**
         * Retrieves current clock time.
         *
         * @return time in nanoseconds
         */
        private long now() {
            return clock.time(System.nanoTime());
        }

        /**
         * Calculates current rotation yaw angle.
         *
         * @return yaw angle in degrees
         */
        private float yaw() {
            return MoleculeRenderer.rotationAngle(now() / 1_000_000_000.0);
        }

        /** Releases camera dragging state. */
        void release() {
            if (camera.isDragging()) camera.release(camera.draggingButton(), yaw(), now());
        }

        /** Disposes preview preparation and resources. */
        @Override
        public void dispose() {
            preparation.close();
            super.dispose();
        }

        /**
         * Renders the viewport background, 3D molecule structure, and status overlays.
         *
         * @param context GUI context
         * @param theme   widget theme entry
         */
        @Override
        public void draw(ModularGuiContext context, WidgetThemeEntry<?> theme) {
            definitionCurrent();
            long currentResources = MoleculeCategoryStyle.reloadRevision();
            if (resources != currentResources) {
                resources = currentResources;
                rebuild();
            }
            Prepared ready = preparation.take();
            if (ready != null) {
                geometry = ready.geometry();
                formula = ready.formula();
                mass = ready.mass();
                revision++;
            }
            int width = getArea().width, height = getArea().height;
            Gui.drawRect(0, 0, width, height, VIEW_BORDER);
            Gui.drawRect(1, 1, width - 1, height - 1, PAPER);
            camera.rendered();
            if (camera.isDragging()) {
                if (Mouse.isButtonDown(camera.draggingButton()) && Display.isActive())
                    camera.drag(context.getMouseX(), context.getMouseY(), camera.draggingButton());
                else release();
            }
            long time = now();
            if (geometry != null) MoleculeRenderer.renderInViewport(geometry, 1, 1, width - 2, height - 2,
                    Math.max(1, Math.min(width - 16, height - 16)), camera.yaw(yaw(), time), camera.pitch(time),
                    camera.panX(), camera.panY(), camera.zoom());
            zoomFeedback.draw(camera.zoom(), width, height, MUTED);
            if (definitionChanged || preparation.isPending() || preparation.failed())
                text(I18n.format(definitionChanged ? "avogadro.preview.definition_changed" :
                                preparation.failed() ? "avogadro.preview.failed" : "avogadro.preview.preparing"),
                        8, height - 16, width - 16, MUTED);
        }

        /**
         * Handles mouse press events for starting camera rotation or resetting the view.
         *
         * @param button mouse button index
         * @return interaction result status
         */
        @Override
        public Result onMousePressed(int button) {
            if (button == 2) {
                resetView();
                return Result.SUCCESS;
            }
            return camera.start(getContext().getMouseX(), getContext().getMouseY(), button, yaw(), now()) ?
                    Result.SUCCESS : Result.IGNORE;
        }

        /**
         * Handles mouse drag events for rotating the camera.
         *
         * @param button  mouse button index
         * @param elapsed elapsed time
         */
        @Override
        public void onMouseDrag(int button, long elapsed) {
            camera.drag(getContext().getMouseX(), getContext().getMouseY(), button);
        }

        /**
         * Handles mouse release events.
         *
         * @param button mouse button index
         * @return {@code true} if handled
         */
        @Override
        public boolean onMouseRelease(int button) {
            return camera.release(button, yaw(), now());
        }

        /**
         * Handles mouse scroll events for zooming.
         *
         * @param direction scroll direction
         * @param amount    scroll amount
         * @return {@code true} if handled
         */
        @Override
        public boolean onMouseScroll(UpOrDown direction, int amount) {
            float previousZoom = camera.zoom();
            boolean handled = camera.scroll(direction.modifier, getContext().getMouseX() - getArea().width / 2f,
                    getContext().getMouseY() - getArea().height / 2f);
            if (camera.zoom() != previousZoom) zoomFeedback.changed(System.nanoTime());
            return handled;
        }
    }
}
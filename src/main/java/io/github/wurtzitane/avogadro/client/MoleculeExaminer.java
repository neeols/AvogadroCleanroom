package io.github.wurtzitane.avogadro.client;

import static io.github.wurtzitane.avogadro.client.StructureStyle.button;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widgets.ButtonWidget;

import io.github.wurtzitane.avogadro.api.ChemicalFormula;
import io.github.wurtzitane.avogadro.api.ElementCatalog;
import io.github.wurtzitane.avogadro.api.MoleculeInspection;
import io.github.wurtzitane.avogadro.item.MoleculeItems;
import io.github.wurtzitane.avogadro.molecule.AtomElement;
import io.github.wurtzitane.avogadro.molecule.MoleculeDef;
import io.github.wurtzitane.avogadro.molecule.MoleculeStore;
import io.github.wurtzitane.avogadro.molecule.MoleculeStructure;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;

/**
 * Client-only examination interface for molecules, crystal cells, polymers, and chemical elements, providing
 * interactive 3D inspection, structural details, component breakdowns, and isotope selection.
 */
public final class MoleculeExaminer {

    /** Standard UI ink color constant derived from structure styling. */
    private static final int INK = StructureStyle.INK;

    /** Standard UI muted text color constant derived from structure styling. */
    private static final int MUTED = StructureStyle.MUTED;

    /** Standard UI accent color constant derived from structure styling. */
    private static final int ACCENT = StructureStyle.ACCENT;

    private MoleculeExaminer() {}

    /**
     * Opens the examiner interface for a given molecule definition. Automatically routes polymers and crystal cells to
     * their respective specialized examiners.
     *
     * @param selected the molecule definition to examine, or {@code null} to abort
     */
    public static void open(MoleculeDef selected) {
        if (selected == null)
            return;
        if (io.github.wurtzitane.avogadro.api.PolymerNetworkDefinition.of(selected) != null) {
            PolymerNetworkExaminer.open(selected);
            return;
        }
        if (io.github.wurtzitane.avogadro.api.PolymerDefinition.of(selected) != null) {
            PolymerExaminer.open(selected);
            return;
        }
        if (io.github.wurtzitane.avogadro.api.CrystalCell.of(selected) != null) {
            CrystalExaminer.open(selected);
            return;
        }
        open(selected, null, null, null, false);
    }

    /**
     * Opens a single authored element directly in atomic inspection, retaining its isotope, charge and other data.
     * @param selected single-atom element definition
     * @throws IllegalArgumentException if the definition is not a single real element atom
     */
    public static void openAtom(MoleculeDef selected) {
        if (!AtomicPreview.supports(selected))
            throw new IllegalArgumentException("Atomic inspection requires a single element atom");
        open(selected, null, null, null, false);
    }

    /**
     * Opens the examiner interface focused on constituent atoms of a molecule with a return callback.
     *
     * @param selected the molecule definition to examine
     * @param onReturn the callback runnable executed when closing or returning from the examiner
     */
    static void openAtoms(MoleculeDef selected, Runnable onReturn) {
        if (selected != null)
            open(selected, null, onReturn, null, false);
    }

    /**
     * Opens a catalog element without publishing a molecule or altering pack-authored data, utilizing its default
     * reference mass number.
     *
     * @param symbol the chemical element symbol (e.g., "H", "Fe", "U")
     * @param onReturn the callback runnable executed upon returning
     */
    public static void openElement(String symbol, Runnable onReturn) {
        openElement(symbol, ElementCatalog.bySymbol(symbol).referenceMassNumber(), onReturn, mass -> {});
    }

    /**
     * Opens a catalog element with a specified mass number, return action, and isotope change listener.
     *
     * @param symbol the chemical element symbol
     * @param massNumber the specific isotope mass number
     * @param onReturn the callback runnable executed upon returning
     * @param onIsotopeChanged the consumer notified when the selected isotope mass changes
     */
    public static void openElement(String symbol, int massNumber, Runnable onReturn, IntConsumer onIsotopeChanged) {
        var element = ElementCatalog.bySymbol(symbol);
        open(referenceDefinition(element.symbol(), massNumber), element.symbol(), onReturn, onIsotopeChanged, false);
    }

    /**
     * Opens a table element originating directly from the periodic table screen, preserving parent hierarchy.
     *
     * @param symbol the chemical element symbol
     * @param massNumber the specific isotope mass number
     * @param onReturn the callback runnable executed upon returning
     * @param onIsotopeChanged the consumer notified when the selected isotope mass changes
     */
    static void openTableElement(String symbol, int massNumber, Runnable onReturn, IntConsumer onIsotopeChanged) {
        open(referenceDefinition(symbol, massNumber), symbol, onReturn, onIsotopeChanged, true);
    }

    /**
     * Synthesizes and returns a reference molecule definition for a single element and specific isotope.
     *
     * @param symbol the chemical element symbol
     * @param massNumber the isotope mass number
     * @return a constructed single-atom {@link MoleculeDef}
     */
    static MoleculeDef referenceDefinition(String symbol, int massNumber) {
        var element = ElementCatalog.bySymbol(symbol);
        var configuration = ElementCatalog.configuration(symbol, massNumber);
        ResourceLocation id = new ResourceLocation("avogadro",
                "reference/" + symbol.toLowerCase(java.util.Locale.ROOT) + "_" + massNumber);
        var structure = MoleculeStructure.builder(id.toString(), symbol);
        structure.addAtom(AtomElement.bySymbol(symbol), 0, 0, 0);
        var inspection = new MoleculeInspection(Map.of(symbol, configuration), Map.of(), List.of(),
                Map.of("classification", "element", "reference_isotope", symbol + "-" + massNumber, "source",
                        element.atomicNumber() >= 1 && element.atomicNumber() <= 118
                                ? "NUBASE2020"
                                : ElementCatalog.fact(symbol, "source")));
        return new MoleculeDef(id, structure.build(), symbol, List.of(), List.of(), List.of(), List.of(), List.of(),
                false, element.name() + "-" + massNumber, false, inspection);
    }

    /**
     * Generates a formatted display label for an element isotope, appending its colloquial name if available.
     *
     * @param symbol the chemical element symbol
     * @param massNumber the isotope mass number
     * @return a human-readable isotope string label
     */
    static String isotopeLabel(String symbol, int massNumber) {
        String name = ElementCatalog.fact(symbol, "isotope_name_" + massNumber);
        return symbol + "-" + massNumber + (name.isEmpty() ? "" : " (" + name + ")");
    }

    /**
     * Core routine for initializing and presenting the modular examiner screen.
     *
     * @param selected the primary molecule definition being inspected
     * @param referenceSymbol the optional element symbol if inspecting a pure element
     * @param onReturn optional callback runnable when returning
     * @param onIsotopeChanged optional consumer triggered on isotope alterations
     * @param keepTableParent whether to maintain the periodic table screen as the parent background
     */
    private static void open(MoleculeDef selected, String referenceSymbol, Runnable onReturn,
            IntConsumer onIsotopeChanged, boolean keepTableParent) {
        Minecraft client = Minecraft.getMinecraft();
        ViewerWindows.schedule(() -> {
            if (client.player == null)
                return;
            ElementActions.beginNavigation();
            boolean returnToParent = keepTableParent && PeriodicTableScreen.isOpen();
            if (!returnToParent) {
                ViewerWindows.prepareOpen();
            }
            ScaledResolution resolution = ViewerWindows.resolution();
            int width = Math.max(296, Math.min(430, resolution.getScaledWidth() - 24));
            int height = Math.max(190, Math.min(300, resolution.getScaledHeight() - 24));
            int windowActionWidth = ViewerWindows.available() ? 24 : 0;
            int viewTop = selected.structure().atoms().size() == 1 ? 32 : 40;
            int viewWidth = width - 20, viewHeight = height - viewTop - 38;
            InspectionWidget model = new InspectionWidget(selected, viewWidth, viewHeight);
            model.referenceSymbol = referenceSymbol;
            model.onIsotopeChanged = onIsotopeChanged;
            if (model.isSingleAtom())
                model.focusAtom(0);
            ModularScreen[] screen = new ModularScreen[1];
            model.pos(10, viewTop).size(viewWidth, viewHeight);
            model.compactWidth = width - 160;
            model.updateSidebar();
            ModularPanel panel = ModularPanel.defaultPanel("molecule_examiner", width, height)
                    .background(StructureStyle.surface(StructureStyle::PANEL, StructureStyle::BORDER));
            panel.child(new Widget<>().size(width, 38)
                    .background((context, x, y, w, h, theme) -> StructureStyle.header(model.breadcrumb(),
                            model.statistics(), x, y, w,
                            (ElementActions.hasMaterialForms(referenceSymbol) ? 250 : 144) + windowActionWidth)));
            panel.child(model.setEnabledIf(w -> !model.showSettings && !model.showIsotopes));
            panel.child(new DetailsWidget(model).pos(width - 142, viewTop).size(132, viewHeight)
                    .setEnabledIf(w -> model.sidebarVisible() && !model.showSettings && !model.showIsotopes));
            panel.child(new IsotopeListWidget(model).pos(10, viewTop).size(width - 20, viewHeight)
                    .setEnabledIf(w -> model.showIsotopes));
            panel.child(
                    ExaminerPresentation.controls(!model.isSingleAtom(), StructureStyle.MUTED()).pos(width - 130 - windowActionWidth, 11)
                            .setEnabledIf(w -> !model.showSettings && !model.showIsotopes));
            panel.child(button(
                    () -> I18n.format(model.clock.paused ? "avogadro.examiner.resume" : "avogadro.examiner.pause"),
                    model::togglePaused).pos(10, height - 28).size(54, 18)
                    .setEnabledIf(w -> !model.showSettings && !model.showIsotopes));
            panel.child(button(() -> I18n.format("avogadro.examiner.reset"), model::resetView).pos(70, height - 28)
                    .size(60, 18).setEnabledIf(w -> !model.showSettings && !model.showIsotopes));
            panel.child(button(
                    () -> I18n.format(model.showComponents
                            ? "avogadro.examiner.details"
                            : model.isMixture() ? "avogadro.examiner.phases" : "avogadro.examiner.component"),
                    model::toggleComponents, () -> model.showComponents, false).pos(136, height - 28).size(86, 18)
                    .setEnabledIf(w -> model.hasComponents() && !model.showSettings && !model.showIsotopes));
            panel.child(button(() -> I18n.format("avogadro.periodic.isotope.choose"), model::toggleIsotopes)
                    .pos(136, height - 28).size(90, 18)
                    .setEnabledIf(w -> referenceSymbol != null
                            && ElementCatalog.bySymbol(referenceSymbol).isotopes().size() > 1 && !model.showSettings
                            && !model.showIsotopes));
            panel.child(button(() -> I18n.format("avogadro.examiner.back"), () -> {
                if (model.showSettings)
                    model.showSettings = false;
                else if (model.showIsotopes)
                    model.showIsotopes = false;
                else if (referenceSymbol != null || onReturn != null && !model.canBack())
                    ViewerWindows.close(screen[0]);
                else
                    model.back();
            }).pos(width - 64, height - 28).size(54, 18).setEnabledIf(w -> referenceSymbol != null || onReturn != null
                    || model.canBack() || model.showSettings || model.showIsotopes));
            panel.child(button(() -> I18n.format(model.showSettings ? "gui.done" : "avogadro.examiner.settings"),
                    model::toggleSettings).pos(width - 108 - windowActionWidth, 8).size(72, 20));
            panel.child(button(() -> I18n.format("avogadro.examiner.material_forms"), () -> {
                screen[0].openParentOnClose(false);
                ElementActions.openMaterialForms(referenceSymbol, ViewerWindows.returnTo(screen[0],
                        () -> openElement(referenceSymbol,
                                model.definition.inspection().atom(model.definition.structure(), 0).massNumber(),
                                onReturn, onIsotopeChanged)));
            }).pos(width - 216 - windowActionWidth, 8).size(102, 20)
                    .setEnabledIf(w -> ElementActions.hasMaterialForms(referenceSymbol) && !model.showSettings));
            AtomViewSettings.addTo(panel, viewTop, width, viewHeight, () -> model.showSettings, model.camera::rendered);
            panel.child(StructureStyle.closeButton(() -> ViewerWindows.close(screen[0])).pos(width - 24, 8));
            screen[0] = new ModularScreen("avogadro", panel) {
                private boolean returned;

                @Override
                public boolean onKeyPressed(char character, int key) {
                    if (key == Keyboard.KEY_TAB) {
                        if (model.showIsotopes || model.showSettings || model.isSingleAtom())
                            return super.onKeyPressed(character, key);
                        model.focusNextAtom(net.minecraft.client.gui.GuiScreen.isShiftKeyDown() ? -1 : 1);
                        return true;
                    }
                    if (ViewerWindows.isCloseKey(this, key)) {
                        if (key == Keyboard.KEY_ESCAPE && net.minecraft.client.gui.GuiScreen.isShiftKeyDown()) {
                            returned = true;
                            ElementActions.closeAll();
                            return true;
                        }
                        if (model.showSettings) {
                            model.showSettings = false;
                            return true;
                        }
                        if (model.showIsotopes) {
                            model.showIsotopes = false;
                            return true;
                        }
                        ViewerWindows.close(this);
                        return true;
                    }
                    if (AvogadroKeys.PAUSE_MOLECULE.isActiveAndMatches(key)) {
                        if (!Keyboard.isRepeatEvent())
                            model.togglePaused();
                        return true;
                    }
                    return super.onKeyPressed(character, key);
                }

                @Override
                public void onClose() {
                    if (!ViewerWindows.isHosted(this))
                        model.disposeRenderer();
                    super.onClose();
                    if (!ViewerWindows.isHosted(this) && ElementActions.returningAllowed() && !returnToParent
                            && !returned && onReturn != null && client.world != null) {
                        returned = true;
                        var world = client.world;
                        ClientProxy.afterScreenClosed(() -> {
                            if (ElementActions.returningAllowed() && client.world == world && client.player != null
                                    && client.currentScreen == null)
                                onReturn.run();
                        });
                    }
                }
            }.openParentOnClose(returnToParent).pausesGame(false);
            UiThemes.menu(screen[0], () -> (referenceSymbol == null ? "molecule_examiner" : "atom_examiner")
                    + (model.showSettings ? "/settings" : model.showIsotopes ? "/isotopes" : model.showComponents ? "/components" : ""));
            ViewerWindows.addButton(panel, () -> open(model.definition, referenceSymbol, null, mass -> {}, false),
                    width - 46, 9);
            ViewerWindows.open(screen[0]);
        });
    }

    /**
     * Draws a trimmed string using the specified font renderer.
     *
     * @param font the font renderer instance
     * @param value the string value to draw
     * @param x the x-coordinate
     * @param y the y-coordinate
     * @param width the maximum allowable width
     * @param color the text color integer
     */
    private static void text(FontRenderer font, String value, int x, int y, int width, int color) {
        font.drawString(font.trimStringToWidth(value, Math.max(1, width)), x, y, color);
    }

    /**
     * Manages animation timing, pause states, and elapsed duration calculations for 3D model rotations.
     */
    static final class AnimationClock {

        /** The initial timestamp when the clock started in nanoseconds. */
        private final long started;

        /** The recorded pause timestamp and accumulated paused duration in nanoseconds. */
        private long pausedAt, pausedNanos;

        /** Flag indicating whether the clock is currently paused. */
        private boolean paused;

        /**
         * Creates a animation clock starting at the specified time.
         *
         * @param now the starting time in nanoseconds
         */
        AnimationClock(long now) {
            started = now;
        }

        /**
         * Calculates the active elapsed time in nanoseconds, accounting for pause periods.
         *
         * @param now the current system time in nanoseconds
         * @return the adjusted elapsed time
         */
        long time(long now) {
            return Math.max(0, (paused ? pausedAt : now) - started - pausedNanos);
        }

        /**
         * Toggles the paused state of the animation clock.
         *
         * @param now the current system time in nanoseconds
         */
        void toggle(long now) {
            if (paused)
                pausedNanos += now - pausedAt;
            else
                pausedAt = now;
            paused = !paused;
        }
    }

    /**
     * Interactive widget managing 3D viewport rendering, atom picking, camera adjustments, and state persistence for
     * molecule structures.
     */
    public static final class InspectionWidget extends Widget<InspectionWidget> implements Interactable {

        private MoleculeDef definition;
        private AtomicInspectionRenderer renderer;
        private final PreviewPreparation<AtomicInspectionRenderer> preparation = new PreviewPreparation<>();
        private final MoleculeCamera camera;
        private final int fullWidth;
        private int compactWidth;
        private final ExaminerPresentation.ZoomFeedback zoomFeedback = new ExaminerPresentation.ZoomFeedback();
        private final AnimationClock clock = new AnimationClock(System.nanoTime());
        private final ArrayDeque<MoleculeDef> history = new ArrayDeque<>();
        private int focused = -1, componentOffset;
        private int pressX, pressY, pressedButton = -1, pressedAtom = -1;
        private boolean moved, showComponents, showSettings;
        private boolean showIsotopes;
        private String referenceSymbol;
        private int isotopeOffset;
        private IntConsumer onIsotopeChanged;
        private long resources = Long.MIN_VALUE, componentRevision = Long.MIN_VALUE;
        private long definitionRevision = Long.MIN_VALUE;
        private boolean disposed, registeredDefinition, definitionChanged;
        private List<MoleculeDef> components = List.of();

        /**
         * Constructs an inspection widget for the given molecule definition and dimensions.
         *
         * @param definition the initial molecule definition
         * @param width the viewport width
         * @param height the viewport height
         */
        InspectionWidget(MoleculeDef definition, int width, int height) {
            fullWidth = compactWidth = width;
            camera = new MoleculeCamera(width, height);
            select(definition);
        }

        @Override
        public void draw(ModularGuiContext context, WidgetThemeEntry<?> theme) {
            if (disposed)
                return;
            camera.resizeViewport(getArea().width, getArea().height);
            refreshDefinition();
            AtomicInspectionRenderer prepared = preparation.take();
            if (prepared != null)
                renderer = prepared;
            Gui.drawRect(0, 0, getArea().width, getArea().height, UiThemes.color("viewport.border", 0xFFD8DED2));
            Gui.drawRect(1, 1, getArea().width - 1, getArea().height - 1, UiThemes.color("viewport.background", 0xFFFAF8F2));
            camera.rendered();
            if (camera.isDragging()) {
                int button = camera.draggingButton();
                if (Mouse.isButtonDown(button) && Display.isActive())
                    updateDrag(button);
                else
                    camera.release(button, automaticYaw(), clock.time(System.nanoTime()));
            }
            if (renderer != null)
                renderer.render(getArea().width, getArea().height, camera, focused, clock.time(System.nanoTime()));
            if (definitionChanged || loading() || preparation.failed())
                text(PixelFont.renderer(),
                        I18n.format(definitionChanged
                                ? "avogadro.preview.definition_changed"
                                : preparation.failed() ? "avogadro.preview.failed" : "avogadro.preview.preparing"),
                        6, getArea().height - 15, getArea().width - 12, StructureStyle.MUTED());
            if (isReady() && !isSingleAtom() && context.isHovered(this)) {
                int hover = renderer.pick(context.getMouseX(), context.getMouseY());
                if (hover >= 0)
                    text(PixelFont.renderer(), hoverAtomLabel(hover), 6, 6, getArea().width - 12, StructureStyle.ACCENT());
            }
            GlStateManager.color(1, 1, 1, 1);
            zoomFeedback.draw(camera.zoom(), getArea().width, getArea().height, StructureStyle.MUTED());
        }

        /**
         * Computes the automatic rotational yaw angle based on elapsed clock time.
         *
         * @return the computed yaw angle in radians or degrees depending on renderer logic
         */
        private float automaticYaw() {
            return MoleculeRenderer.rotationAngle(clock.time(System.nanoTime()) / 1_000_000_000.0);
        }

        @Override
        public Result onMousePressed(int button) {
            pressX = getContext().getMouseX();
            pressY = getContext().getMouseY();
            moved = false;
            pressedButton = button;
            pressedAtom = button == 0 && isReady() ? renderer.pick(pressX, pressY) : -1;
            if (button == 2) {
                resetView();
                return Result.SUCCESS;
            }
            return camera.start(pressX, pressY, button, automaticYaw(), clock.time(System.nanoTime()))
                    ? Result.SUCCESS
                    : Result.IGNORE;
        }

        /**
         * Updates camera dragging state based on mouse displacement.
         *
         * @param button the mouse button being dragged
         */
        private void updateDrag(int button) {
            int x = getContext().getMouseX(), y = getContext().getMouseY();
            moved |= Math.abs(x - pressX) + Math.abs(y - pressY) > 4;
            camera.drag(x, y, button);
        }

        @Override
        public void onMouseDrag(int button, long elapsed) {
            updateDrag(button);
        }

        @Override
        public boolean onMouseRelease(int button) {
            updateDrag(button);
            boolean handled = pressedButton == button;
            camera.release(button, automaticYaw(), clock.time(System.nanoTime()));
            pressedButton = -1;
            if (handled && button == 0 && !moved) {
                if (pressedAtom >= 0 && pressedAtom != focused)
                    focusAtom(pressedAtom);
            }
            return handled;
        }

        @Override
        public boolean onMouseScroll(UpOrDown direction, int amount) {
            float before = camera.zoom();
            boolean handled = camera.scroll(direction.modifier, getContext().getMouseX() - getArea().width / 2f,
                    getContext().getMouseY() - getArea().height / 2f);
            if (camera.zoom() != before)
                zoomFeedback.changed(System.nanoTime());
            return handled;
        }

        /** Toggles the pause state of the inspection clock. */
        void togglePaused() {
            clock.toggle(System.nanoTime());
        }

        /** Toggles the view settings overlay panel. */
        void toggleSettings() {
            releaseCamera();
            showIsotopes = false;
            showSettings = !showSettings;
        }

        /** Releases any active camera dragging operations. */
        private void releaseCamera() {
            if (camera.isDragging())
                camera.release(camera.draggingButton(), automaticYaw(), clock.time(System.nanoTime()));
        }

        /**
         * Returns the isotope label for the current reference symbol and definition.
         *
         * @return formatted isotope string
         */
        String isotopeLabel() {
            if (referenceSymbol == null)
                return "";
            return MoleculeExaminer.isotopeLabel(referenceSymbol,
                    definition.inspection().atom(definition.structure(), 0).massNumber());
        }

        /** Toggles the isotope selection list overlay. */
        void toggleIsotopes() {
            releaseCamera();
            showSettings = false;
            showIsotopes = !showIsotopes;
            if (showIsotopes) {
                var choices = ElementCatalog.bySymbol(referenceSymbol).isotopes();
                int mass = definition.inspection().atom(definition.structure(), 0).massNumber();
                for (int i = 0; i < choices.size(); i++) {
                    if (choices.get(i).massNumber() == mass) {
                        isotopeOffset = Math.max(0, i - 3);
                        break;
                    }
                }
            }
        }

        /**
         * Selects a specific isotope mass number for the reference element.
         *
         * @param massNumber the isotope mass number to select
         */
        void selectIsotope(int massNumber) {
            if (referenceSymbol == null)
                return;
            MoleculeDef next = referenceDefinition(referenceSymbol, massNumber);
            definition = next;
            registeredDefinition = false;
            definitionChanged = false;
            requestRenderer();
            focused = 0;
            showIsotopes = false;
            if (onIsotopeChanged != null)
                onIsotopeChanged.accept(massNumber);
        }

        /** Resets the camera view and zoom feedback. */
        void resetView() {
            camera.reset();
            zoomFeedback.clear();
        }

        /** Checks if the inspected molecule consists of only a single atom. */
        boolean isSingleAtom() {
            return definition.structure().atoms().size() == 1 && InspectionModelData.canInspect(definition.structure(), 0);
        }

        /**
         * Returns the hover label for an atom at the specified index.
         *
         * @param index the atom index
         * @return the formatted atom label string
         */
        String hoverAtomLabel(int index) {
            if (isSingleAtom() || index == focused || index < 0 || index >= definition.structure().atoms().size())
                return "";
            var atom = definition.structure().atoms().get(index);
            String symbol = atom.annotation().label().isEmpty() ? atom.element().symbol() : atom.annotation().label();
            var stereo = definition.structure().stereochemistry().get(index);
            return symbol + " #" + (index + 1) + (stereo == null ? "" : " \u00b7 CIP " + stereo)
                    + (atom.annotation().radicalElectrons() == 0 ? "" : " \u00b7 unpaired: " + atom.annotation().radicalElectrons());
        }

        /**
         * Generates the navigational breadcrumb string for the header.
         *
         * @return the breadcrumb description
         */
        String breadcrumb() {
            String name = MoleculeItems.displayName(definition);
            if (focused >= 0 && !isSingleAtom())
                name += " / " + definition.structure().atoms().get(focused).element().symbol() + " " + (focused + 1);
            return name;
        }

        /** Determines if the inspected definition represents a molecular mixture. */
        boolean isMixture() {
            return io.github.wurtzitane.avogadro.api.MoleculeClassification
                    .of(definition) == io.github.wurtzitane.avogadro.api.MoleculeClassification.MIXTURE;
        }

        /**
         * Formats structural statistics (formula, atom count, bond count) for the header.
         *
         * @return formatted statistics string
         */
        String statistics() {
            if (isSingleAtom())
                return "";
            if (isMixture())
                return I18n.format("avogadro.examiner.mixture_structure");
            return I18n.format("avogadro.examiner.structure", ChemicalFormula.display(definition.formula()),
                    definition.structure().atoms().size(), definition.structure().bonds().size());
        }

        /**
         * Focuses inspection on a specific atom index.
         *
         * @param index the target atom index
         */
        void focusAtom(int index) {
            if (index != focused && InspectionModelData.canInspect(definition.structure(), index)) {
                focused = index;
                resetView();
                updateSidebar();
            }
        }

        /** Skip unresolved external groups, which have no element or nucleus to inspect. */
        void focusNextAtom(int direction) {
            int count = definition.structure().atoms().size();
            int start = focused < 0 ? (direction < 0 ? 0 : -1) : focused;
            for (int step = 1; step <= count; step++) {
                int index = Math.floorMod(start + direction * step, count);
                if (InspectionModelData.canInspect(definition.structure(), index)) {
                    focusAtom(index);
                    return;
                }
            }
        }

        /** Checks if backward navigation in history or focus is available. */
        boolean canBack() {
            return (focused >= 0 && !isSingleAtom()) || !history.isEmpty();
        }

        /** Executes a back navigation step, clearing atom focus or reverting history. */
        void back() {
            if (focused >= 0 && !isSingleAtom()) {
                focused = -1;
                resetView();
                updateSidebar();
            } else if (!history.isEmpty())
                select(history.removeLast());
        }

        /**
         * Returns the list of available component definitions for mixtures or compounds.
         *
         * @return list of component molecule definitions
         */
        private List<MoleculeDef> availableComponents() {
            long revision = MoleculeStore.revision();
            if (componentRevision != revision) {
                components = definition.inspection().components().stream().map(MoleculeStore::get)
                        .filter(next -> next != null && !next.id().equals(definition.id())
                                && history.stream().noneMatch(parent -> parent.id().equals(next.id())))
                        .toList();
                componentRevision = revision;
            }
            return components;
        }

        /** Checks whether the molecule has selectable sub-components. */
        boolean hasComponents() {
            return !availableComponents().isEmpty();
        }

        /** Determines if the side panel is currently visible. */
        boolean sidebarVisible() {
            return focused >= 0 || showComponents;
        }

        /** Updates the widget width based on sidebar visibility. */
        private void updateSidebar() {
            width(sidebarVisible() ? compactWidth : fullWidth);
        }

        /** Toggles component view mode. */
        private void toggleComponents() {
            showComponents = !showComponents;
            updateSidebar();
        }

        /**
         * Selects a sub-component by its index.
         *
         * @param index the component index
         */
        void selectComponent(int index) {
            List<MoleculeDef> choices = availableComponents();
            if (index < 0 || index >= choices.size())
                return;
            MoleculeDef selected = choices.get(index);
            if (io.github.wurtzitane.avogadro.api.CrystalCell.of(selected) != null) {
                MoleculeDef parent = definition;
                CrystalExaminer.open(selected, () -> MoleculeExaminer.open(parent));
                return;
            }
            history.addLast(definition);
            select(selected);
        }

        /**
         * Selects a new molecule definition to inspect.
         *
         * @param next the next molecule definition
         */
        void select(MoleculeDef next) {
            definition = next;
            registeredDefinition = MoleculeStore.get(next.id()) == next;
            definitionChanged = false;
            requestRenderer();
            focused = isSingleAtom() ? 0 : -1;
            componentOffset = 0;
            componentRevision = Long.MIN_VALUE;
            showComponents = isMixture() && hasComponents();
            resetView();
            updateSidebar();
        }

        /** Disposes the renderer and cleans up preparation resources. */
        void disposeRenderer() {
            disposed = true;
            preparation.close();
            closeRenderer();
            camera.reset();
        }

        /** Checks whether the inspector renderer is fully ready for drawing. */
        boolean isReady() {
            return renderer != null && renderer.isReady();
        }

        /** Checks if asset preparation or rendering is currently pending. */
        boolean loading() {
            return preparation.isPending() || renderer != null && !renderer.isReady();
        }

        /** Closes and nullifies the current renderer instance. */
        private void closeRenderer() {
            if (renderer != null)
                renderer.close();
            renderer = null;
        }

        /** Requests an asynchronous update and build of the inspection renderer. */
        private void requestRenderer() {
            closeRenderer();
            resources = MoleculeCategoryStyle.reloadRevision();
            definitionRevision = MoleculeStore.revision();
            if (disposed || definitionChanged)
                return;
            MoleculeDef selected = definition;
            preparation.request(() -> new AtomicInspectionRenderer(selected));
        }

        /** Refreshes definition state and reloads renderers if revisions change. */
        private void refreshDefinition() {
            if (definitionChanged)
                return;
            boolean changed = resources != MoleculeCategoryStyle.reloadRevision();
            long revision = MoleculeStore.revision();
            if (definitionRevision != revision) {
                definitionRevision = revision;
                MoleculeDef current = registeredDefinition ? MoleculeStore.get(definition.id()) : null;
                if (registeredDefinition && current == null) {
                    definitionChanged = true;
                    preparation.close();
                    closeRenderer();
                    return;
                }
                if (registeredDefinition && current != definition) {
                    definition = current;
                    if (focused >= current.structure().atoms().size())
                        focused = -1;
                    componentRevision = Long.MIN_VALUE;
                    componentOffset = 0;
                    updateSidebar();
                    changed = true;
                }
            }
            if (changed)
                requestRenderer();
        }

        @Override
        public void dispose() {
            disposeRenderer();
            super.dispose();
        }
    }

    /**
     * UI widget rendering and managing the scrollable isotope selection list.
     */
    private static final class IsotopeListWidget extends Widget<IsotopeListWidget> implements Interactable {

        /** Top vertical offset for rendering isotope rows. */
        private static final int ROW_TOP = 20;

        /** Associated inspection model instance. */
        private final InspectionWidget model;

        /**
         * Constructs an isotope list widget linked to the inspection model.
         *
         * @param model the inspection widget model
         */
        IsotopeListWidget(InspectionWidget model) {
            this.model = model;
        }

        /** Calculates the number of visible rows that fit in the widget height. */
        private int rows() {
            return Math.max(1, (getArea().height - ROW_TOP - 4) / 20);
        }

        @Override
        public void draw(ModularGuiContext context, WidgetThemeEntry<?> theme) {
            model.camera.rendered();
            var element = ElementCatalog.bySymbol(model.referenceSymbol);
            var choices = element.isotopes();
            model.isotopeOffset = Math.clamp(model.isotopeOffset, 0, Math.max(0, choices.size() - rows()));
            Gui.drawRect(0, 0, getArea().width, getArea().height, ElementCard.paperColor());
            FontRenderer font = PixelFont.renderer();
            text(font, I18n.format("avogadro.periodic.isotope.title", element.name()), 8, 5, getArea().width - 16, StructureStyle.INK());
            int selected = model.definition.inspection().atom(model.definition.structure(), 0).massNumber();
            for (int row = 0; row < rows() && row + model.isotopeOffset < choices.size(); row++) {
                var isotope = choices.get(row + model.isotopeOffset);
                int y = ROW_TOP + row * 20;
                boolean hover = context.isHovered(this) && context.getMouseY() >= y && context.getMouseY() < y + 18;
                Gui.drawRect(4, y, getArea().width - 9, y + 18,
                        isotope.massNumber() == selected ? UiThemes.color("button.selected.background", 0xFFDCE9E2) : hover ? UiThemes.color("button.hover.background", 0xFFFDFCF7) : StructureStyle.SURFACE());
                String label = isotopeLabel(element.symbol(), isotope.massNumber());
                if (isotope.massNumber() == element.referenceMassNumber())
                    label += "  " + I18n.format("avogadro.periodic.isotope.reference");
                text(font, label, 9, y + 5, getArea().width - 100, StructureStyle.INK());
                String neutrons = I18n.format("avogadro.periodic.isotope.neutrons",
                        isotope.massNumber() - element.atomicNumber());
                text(font, neutrons, getArea().width - 91, y + 5, 80, StructureStyle.MUTED());
            }
            if (choices.size() > rows()) {
                int track = rows() * 20;
                int thumb = Math.max(10, track * rows() / choices.size());
                int top = model.isotopeOffset * (track - thumb) / (choices.size() - rows());
                Gui.drawRect(getArea().width - 6, ROW_TOP + top, getArea().width - 3, ROW_TOP + top + thumb,
                        UiThemes.color("button.selected.border", 0xFF3D7979));
            }
        }

        @Override
        public Result onMousePressed(int button) {
            if (button != 0)
                return Result.IGNORE;
            int y = getContext().getMouseY();
            int row = (y - ROW_TOP) / 20;
            var choices = ElementCatalog.bySymbol(model.referenceSymbol).isotopes();
            if (y >= ROW_TOP && (y - ROW_TOP) % 20 < 18 && row < rows() && model.isotopeOffset + row < choices.size()) {
                Interactable.playButtonClickSound();
                model.selectIsotope(choices.get(model.isotopeOffset + row).massNumber());
                return Result.SUCCESS;
            }
            return Result.ACCEPT;
        }

        @Override
        public boolean onMouseScroll(UpOrDown direction, int amount) {
            int size = ElementCatalog.bySymbol(model.referenceSymbol).isotopes().size();
            model.isotopeOffset = Math.clamp(model.isotopeOffset - direction.modifier * 3, 0,
                    Math.max(0, size - rows()));
            return true;
        }
    }

    /**
     * UI widget displaying detailed element cards or component/phase breakdown lists in the sidebar.
     */
    private static final class DetailsWidget extends Widget<DetailsWidget> implements Interactable {

        /** Associated inspection model instance. */
        private final InspectionWidget model;

        /** Cached qualifier string for dynamic tooltips. */
        private String hoveredQualifier = "";

        /**
         * Constructs a details widget linked to the inspection model.
         *
         * @param model the inspection widget model
         */
        DetailsWidget(InspectionWidget model) {
            this.model = model;
            tooltipDynamic(tooltip -> {
                if (!hoveredQualifier.isEmpty())
                    tooltip.addLine(hoveredQualifier);
            });
        }

        @Override
        public void drawForeground(ModularGuiContext context) {
            String qualifier = model.focused >= 0 && !model.showComponents && context.isHovered(this)
                    ? ElementCard.configurationTooltip(model.definition, model.focused, getArea().width,
                            getArea().height, context.getMouseX(), context.getMouseY())
                    : "";
            if (!qualifier.equals(hoveredQualifier)) {
                hoveredQualifier = qualifier;
                markTooltipDirty();
            }
            if (!qualifier.isEmpty())
                super.drawForeground(context);
        }

        /** Calculates the number of displayable sidebar rows. */
        private int rows() {
            return Math.max(1, (getArea().height - 30) / 22);
        }

        /**
         * Draws a vertical scrollbar for list components.
         *
         * 5 * @param total total items count
         *
         * @param visible visible items count
         * @param offset current scroll offset
         * @param pitch row pitch in pixels
         */
        private void scrollbar(int total, int visible, int offset, int pitch) {
            if (total <= visible)
                return;
            int track = visible * pitch;
            int thumb = Math.max(10, track * visible / total);
            int y = 26 + offset * (track - thumb) / (total - visible);
            Gui.drawRect(getArea().width - 4, 26, getArea().width - 2, 26 + track, StructureStyle.RULE());
            Gui.drawRect(getArea().width - 4, y, getArea().width - 2, y + thumb, 0xFF000000 | StructureStyle.ACCENT());
        }

        @Override
        public void draw(ModularGuiContext context, WidgetThemeEntry<?> theme) {
            FontRenderer font = PixelFont.renderer();
            Gui.drawRect(0, 0, getArea().width, getArea().height, StructureStyle.PAPER());
            if (model.showComponents && model.hasComponents()) {
                List<MoleculeDef> choices = model.availableComponents();
                text(font, I18n.format(
                        model.isMixture() ? "avogadro.examiner.phase_count" : "avogadro.examiner.component_count",
                        choices.size()), 8, 8, getArea().width - 16, StructureStyle.ACCENT());
                model.componentOffset = Math.clamp(model.componentOffset, 0, Math.max(0, choices.size() - rows()));
                for (int row = 0; row < rows() && row + model.componentOffset < choices.size(); row++) {
                    int y = 26 + row * 22;
                    boolean hover = context.isHovered(this) && context.getMouseX() >= 4
                            && context.getMouseX() < getArea().width - 6 && context.getMouseY() >= y
                            && context.getMouseY() < y + 20;
                    Gui.drawRect(4, y, getArea().width - 6, y + 20, hover ? UiThemes.color("button.selected.background", 0xFFDCE9E2) : StructureStyle.SURFACE());
                    text(font, MoleculeItems.displayName(choices.get(row + model.componentOffset)), 8, y + 6,
                            getArea().width - 20, StructureStyle.INK());
                }
                scrollbar(choices.size(), rows(), model.componentOffset, 22);
                return;
            }
            if (model.focused >= 0) {
                ElementCard.draw(model.definition, model.focused, getArea().width, getArea().height);
            }
        }

        @Override
        public Result onMousePressed(int button) {
            if (button != 0 || !model.showComponents || getContext().getMouseY() < 26)
                return Result.IGNORE;
            if ((getContext().getMouseY() - 26) % 22 >= 20)
                return Result.IGNORE;
            int row = (getContext().getMouseY() - 26) / 22;
            if (row >= rows() || row + model.componentOffset >= model.availableComponents().size())
                return Result.IGNORE;
            Interactable.playButtonClickSound();
            model.selectComponent(row + model.componentOffset);
            return Result.SUCCESS;
        }

        @Override
        public boolean onMouseScroll(UpOrDown direction, int amount) {
            if (model.showComponents) {
                model.componentOffset = Math.clamp(model.componentOffset - direction.modifier * 2, 0,
                        Math.max(0, model.availableComponents().size() - rows()));
            } else
                return false;
            return true;
        }
    }
}

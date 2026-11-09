package io.github.wurtzitane.avogadro.client;

import static io.github.wurtzitane.avogadro.client.StructureStyle.*;

import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.widget.Widget;

import io.github.wurtzitane.avogadro.api.ChemicalFormula;
import io.github.wurtzitane.avogadro.api.MoleculeWeight;
import io.github.wurtzitane.avogadro.api.PolymerDefinition;
import io.github.wurtzitane.avogadro.api.PolymerGeometry;
import io.github.wurtzitane.avogadro.api.StructureFragment;
import io.github.wurtzitane.avogadro.item.MoleculeItems;
import io.github.wurtzitane.avogadro.molecule.MoleculeDef;
import io.github.wurtzitane.avogadro.molecule.MoleculeStore;
import io.github.wurtzitane.avogadro.molecule.MoleculeStructure;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.resources.I18n;

import org.lwjgl.input.Keyboard;

/** A finite, end-capped preview of an authored repeat unit; no bulk chain length is assumed. */
public final class PolymerExaminer {

    private PolymerExaminer() {}

    /**
     * Opens the polymer examiner screen for a given molecule definition.
     *
     * @param definition molecule definition
     */
    public static void open(MoleculeDef definition) {
        Minecraft client = Minecraft.getMinecraft();
        ViewerWindows.schedule(() -> {
            if (client.player == null)
                return;
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
     * @param width screen width
     * @param height screen height
     * @return constructed {@link ModularScreen}
     */
    static ModularScreen createScreen(MoleculeDef definition, int width, int height) {
        PolymerDefinition polymer = PolymerDefinition.of(definition);
        if (polymer == null)
            throw new IllegalArgumentException("A polymer repeat unit is required");
        int windowActionWidth = ViewerWindows.available() ? 24 : 0;
        int sideWidth = Math.min(150, width * 2 / 5), viewWidth = width - sideWidth - 26;
        ChainViewport view = new ChainViewport(definition, polymer, viewWidth, height - 74);
        ModularPanel panel = ModularPanel.defaultPanel("polymer_examiner", width, height)
                .background(surface(StructureStyle::PANEL, StructureStyle::BORDER));
        panel.child(new Widget<>().size(width, 38).background((context, x, y, w, h,
                theme) -> header(MoleculeItems.displayName(definition), "", x, y, w, 60 + windowActionWidth)));
        panel.child(ExaminerPresentation.controls(false, StructureStyle.MUTED()).pos(width - 44 - windowActionWidth, 9));
        panel.child(view.pos(10, 40).size(viewWidth, height - 74));
        int sideLeft = width - sideWidth - 10;
        panel.child(new Widget<>().pos(sideLeft, 40).size(sideWidth, height - 50)
                .background((context, x, y, w, h, theme) -> Gui.drawRect(x, y, x + w, y + h, StructureStyle.PAPER())));
        panel.child(new ChainDetails(polymer, view).pos(sideLeft, 40).size(sideWidth, height - 96));
        panel.child(new Widget<>().pos(sideLeft + 10, height - 48).size(sideWidth - 20, 12).background((context, x, y,
                w, h, theme) -> text(I18n.format("avogadro.polymer.units", view.units, view.maxUnits), x, y, w, StructureStyle.INK())));
        int buttonWidth = (sideWidth - 38) / 4;
        int[] steps = {-8, -1, 1, 8};
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
                    if (!Keyboard.isRepeatEvent())
                        view.pause();
                    return true;
                }
                return super.onKeyPressed(character, key);
            }

            @Override
            public void onClose() {
                view.close();
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
         * Creates a chain details view.
         *
         * @param polymer polymer definition
         * @param view chain viewport instance
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
            rows.paragraph(ChemicalFormula.display(polymer.formula()), StructureStyle.INK());
            rows.rule();
            rows.heading(I18n.format("avogadro.polymer.finite_preview"));
            if (view.definitionChanged && view.geometry == null)
                return;
            if (view.preparation.isPending() || view.geometry == null) {
                rows.paragraph(
                        I18n.format(
                                view.preparation.failed() ? "avogadro.preview.failed" : "avogadro.preview.preparing"),
                        StructureStyle.MUTED());
                return;
            }
            rows.paragraph(view.formula, StructureStyle.INK());
            rows.y += 4;
            rows.paragraph(view.mass, StructureStyle.MUTED());
            rows.paragraph(I18n.format("avogadro.polymer.atoms", view.geometry.atoms().size()), StructureStyle.MUTED());
            rows.y += 8;
            rows.paragraph(I18n.format("avogadro.polymer.end_groups"), StructureStyle.MUTED());
        }
    }

    /**
     * Viewport widget rendering 3D polymer chains and handling mouse interactions.
     */
    private static final class ChainViewport extends StructureViewport<ChainViewport> {
        private final PolymerDefinition polymer;
        private final MoleculeDef definition;
        private final boolean registeredDefinition;
        private final PreviewPreparation<Prepared> preparation = new PreviewPreparation<>();
        private record Prepared(MoleculeStructure geometry, String formula, String mass) {
        }
        private MoleculeStructure geometry;
        private final int maxUnits;
        private int units;
        private String formula, mass;
        private long revision, resources = MoleculeCategoryStyle.reloadRevision();
        private long definitions = MoleculeStore.revision();
        private boolean definitionChanged;

        /**
         * Creates a chain viewport.
         *
         * @param definition molecule definition
         * @param polymer polymer definition
         * @param width viewport width
         * @param height viewport height
         */
        ChainViewport(MoleculeDef definition, PolymerDefinition polymer, int width, int height) {
            super(width, height);
            this.definition = definition;
            this.polymer = polymer;
            registeredDefinition = MoleculeStore.get(definition.id()) == definition;
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
            if (!definitionCurrent())
                return;
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
            if (!definitionChanged && registeredDefinition && definitions != current
                    && MoleculeStore.get(definition.id()) != definition) {
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
            if (!definitionCurrent())
                return;
            int selected = Math.clamp(units + delta, 1, maxUnits);
            if (selected == units)
                return;
            release();
            units = selected;
            rebuild();
        }

        /** Applies only the newest completed chain preparation; camera state belongs to the shared viewport. */
        @Override
        protected void updatePreview() {
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
        }

        /** Returns the last complete finite chain. */
        @Override
        protected MoleculeStructure geometry() {
            return geometry;
        }

        /** Uses the same preparation and replacement notices as the other examiners. */
        @Override
        protected String status() {
            if (definitionChanged)
                return I18n.format("avogadro.preview.definition_changed");
            if (preparation.failed())
                return I18n.format("avogadro.preview.failed");
            return preparation.isPending() ? I18n.format("avogadro.preview.preparing") : "";
        }

        /** Cancels preparation when the shared viewport is closed or disposed. */
        @Override
        protected void closePreview() {
            preparation.close();
        }
    }
}

package io.github.wurtzitane.avogadro.client;

import static io.github.wurtzitane.avogadro.client.StructureStyle.*;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;

import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.value.StringValue;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;

import io.github.wurtzitane.avogadro.api.ChemicalFormula;
import io.github.wurtzitane.avogadro.api.NetworkPreviewRequest;
import io.github.wurtzitane.avogadro.api.PolymerNetworkDefinition;
import io.github.wurtzitane.avogadro.api.PolymerPreview;
import io.github.wurtzitane.avogadro.api.client.AvogadroClientApi;
import io.github.wurtzitane.avogadro.api.client.MediaExportRequest;
import io.github.wurtzitane.avogadro.item.MoleculeItems;
import io.github.wurtzitane.avogadro.molecule.MoleculeDef;
import io.github.wurtzitane.avogadro.molecule.MoleculeStore;
import io.github.wurtzitane.avogadro.molecule.MoleculeStructure;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.resources.I18n;

import org.lwjgl.input.Keyboard;

/**
 * Adjustable polymer-network viewer, using Avogadro's normal examiner panel and window host.
 *
 * <p>
 * Parameters and seed are session-local. Changes prepare asynchronously while the previous successful geometry, camera,
 * and pause state remain visible. The export action captures that successful realization, including its reproduction
 * metadata, even while another request is pending.
 *
 * @see PolymerExaminer
 * @see PolymerNetworkDefinition
 */
public final class PolymerNetworkExaminer {
    private PolymerNetworkExaminer() {}

    /**
     * Opens the network examiner through the same window host as the chain and crystal examiners.
     *
     * @param definition a loaded or caller-owned molecule containing a network recipe
     * @throws IllegalArgumentException if the definition contains no network recipe
     */
    public static void open(MoleculeDef definition) {
        if (PolymerNetworkDefinition.of(definition) == null)
            throw new IllegalArgumentException("A network recipe is required");
        ViewerWindows.schedule(() -> {
            if (Minecraft.getMinecraft().player == null)
                return;
            ViewerWindows.prepareOpen();
            ScaledResolution resolution = ViewerWindows.resolution();
            ViewerWindows.open(createScreen(definition, Math.min(520, resolution.getScaledWidth() - 20),
                    Math.min(340, resolution.getScaledHeight() - 20)));
        });
    }

    /**
     * Creates a native ModularUI examiner. Parameter rows, seed controls, and the footer share a fitted sidebar; paging
     * keeps all eight supported parameters reachable at smaller GUI sizes.
     *
     * @param definition molecule supplying the immutable template
     * @param width panel width in GUI pixels
     * @param height panel height in GUI pixels
     * @return a non-pausing examiner with shared camera, close, and optional detached-window actions
     */
    static ModularScreen createScreen(MoleculeDef definition, int width, int height) {
        PolymerNetworkDefinition network = PolymerNetworkDefinition.of(definition);
        if (network == null)
            throw new IllegalArgumentException("A network recipe is required");
        int sideWidth = Math.min(190, width * 2 / 5), side = width - sideWidth - 10;
        int viewWidth = width - sideWidth - 26, windowInset = ViewerWindows.available() ? 24 : 0;
        int controlX = side + 10, controlWidth = sideWidth - 20, half = (controlWidth - 6) / 2;
        ControlLayout layout = ControlLayout.fit(height, network.parameters().size());
        NetworkViewport view = new NetworkViewport(definition, network, viewWidth, height - 100);
        view.rowsPerPage = layout.rows();
        ModularPanel panel = ModularPanel.defaultPanel("polymer_network_examiner", width, height)
                .background(surface(StructureStyle::PANEL, StructureStyle::BORDER));
        panel.child(new Widget<>().size(width, 38).background((context, x, y, w, h,
                theme) -> header(MoleculeItems.displayName(definition), "", x, y, w, 60 + windowInset)));
        panel.child(ExaminerPresentation.controls(false, StructureStyle.MUTED()).pos(width - 44 - windowInset, 9));
        panel.child(view.pos(10, 40).size(viewWidth, height - 100));
        panel.child(button(() -> I18n.format(view.paused ? "avogadro.examiner.resume" : "avogadro.examiner.pause"),
                view::pause, () -> view.paused, false).pos(10, height - 28).size((viewWidth - 12) / 3, 18));
        panel.child(button(() -> I18n.format("avogadro.examiner.reset"), view::resetView)
                .pos(16 + (viewWidth - 12) / 3, height - 28).size((viewWidth - 12) / 3, 18));
        panel.child(button(() -> I18n.format("avogadro.network.export"), view::export)
                .pos(22 + 2 * (viewWidth - 12) / 3, height - 28).size((viewWidth - 12) / 3, 18)
                .setEnabledIf(widget -> view.successful != null));
        panel.child(new Widget<>().pos(10, height - 52).size(viewWidth, 20).background((context, x, y, w, h, theme) -> {
            if (view.successful != null) {
                text(view.formula, x, y, w, StructureStyle.INK());
                text(view.mass, x, y + 11, w, StructureStyle.MUTED());
            }
        }));
        panel.child(new Widget<>().pos(side, 40).size(sideWidth, layout.cardHeight())
                .background(surface(StructureStyle::PAPER, StructureStyle::VIEW_BORDER)));
        for (int row = 0; row < view.rowsPerPage; row++) {
            int local = row, top = layout.start() + row * layout.rowHeight();
            panel.child(new Widget<>().pos(controlX, top).size(controlWidth, 10)
                    .background((context, x, y, w, h, theme) -> {
                        int index = view.page * view.rowsPerPage + local;
                        if (index < network.parameters().size())
                            text(parameterLabel(network.parameters().get(index)), x, y, w, StructureStyle.MUTED());
                    }));
            panel.child(button(() -> "−", () -> view.changeParameter(view.page * view.rowsPerPage + local, -1))
                    .pos(controlX, top + 12).size(22, 18)
                    .setEnabledIf(widget -> view.page * view.rowsPerPage + local < network.parameters().size()));
            panel.child(new Widget<>().pos(controlX + 27, top + 12).size(controlWidth - 54, 18)
                    .background((context, x, y, w, h, theme) -> {
                        surface(StructureStyle::PANEL, StructureStyle::RULE).draw(context, x, y, w, h, theme);
                        String value = view.parameterValue(view.page * view.rowsPerPage + local);
                        var font = PixelFont.renderer();
                        text(value, x + (w - font.getStringWidth(value)) / 2, y + 5, w, StructureStyle.INK());
                    }).setEnabledIf(widget -> view.page * view.rowsPerPage + local < network.parameters().size()));
            panel.child(button(() -> "+", () -> view.changeParameter(view.page * view.rowsPerPage + local, 1))
                    .pos(controlX + controlWidth - 22, top + 12).size(22, 18)
                    .setEnabledIf(widget -> view.page * view.rowsPerPage + local < network.parameters().size()));
        }
        panel.child(new Widget<>().pos(controlX, layout.seedY()).size(controlWidth, 10).background(
                (context, x, y, w, h, theme) -> text(I18n.format("avogadro.network.seed"), x, y, w, StructureStyle.MUTED())));
        TextFieldWidget seed = new TextFieldWidget().setTextColor(StructureStyle.INK())
                .value(new StringValue.Dynamic(() -> view.seedText, value -> view.seedText = value));
        panel.child(seed.name("seed").pos(controlX, layout.seedY() + 12).size(controlWidth, 18)
                .background(inputSurface(seed::isFocused)));
        panel.child(button(() -> I18n.format("avogadro.network.apply"), () -> view.applySeed(seed.getText()))
                .pos(controlX, layout.seedY() + 36).size(half, 18));
        panel.child(button(() -> I18n.format("avogadro.network.next_seed"), view::nextSeed)
                .pos(controlX + half + 6, layout.seedY() + 36).size(half, 18));
        panel.child(button(() -> I18n.format("avogadro.network.reset_parameters"), view::resetParameters)
                .pos(controlX, layout.resetY()).size(controlWidth, 18));
        panel.child(button(() -> "←", () -> view.page = Math.max(0, view.page - 1)).pos(controlX, layout.pagerY())
                .size(28, 18).setEnabledIf(widget -> network.parameters().size() > view.rowsPerPage));
        panel.child(new Widget<>().pos(controlX + 34, layout.pagerY() + 5).size(controlWidth - 68, 10)
                .background((context, x, y, w, h, theme) -> {
                    String value = (view.page + 1) + " / " + ((network.parameters().size() - 1) / view.rowsPerPage + 1);
                    text(value, x + (w - PixelFont.renderer().getStringWidth(value)) / 2, y, w, StructureStyle.MUTED());
                }).setEnabledIf(widget -> network.parameters().size() > view.rowsPerPage));
        panel.child(button(() -> "→",
                () -> view.page = Math.min((network.parameters().size() - 1) / view.rowsPerPage, view.page + 1))
                .pos(controlX + controlWidth - 28, layout.pagerY()).size(28, 18)
                .setEnabledIf(widget -> network.parameters().size() > view.rowsPerPage));
        ModularScreen screen = new ModularScreen("avogadro", panel) {
            @Override
            public boolean onKeyPressed(char character, int key) {
                if (ViewerWindows.isCloseKey(this, key)) {
                    ViewerWindows.close(this);
                    return true;
                }
                if (!seed.isFocused() && AvogadroKeys.PAUSE_MOLECULE.isActiveAndMatches(key)) {
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
     * Resolves explicit translation keys, then built-in names for unlabeled parameters. Authored display labels remain
     * intact, so a resource pack can translate controls without changing parameter identifiers or seeds.
     *
     * @param parameter authored control
     * @return translated label, or the author's literal label
     */
    static String parameterLabel(PolymerNetworkDefinition.Parameter parameter) {
        if (I18n.hasKey(parameter.label()))
            return I18n.format(parameter.label());
        String key = "avogadro.network.parameter." + parameter.name();
        return parameter.label().equals(parameter.name()) && I18n.hasKey(key) ? I18n.format(key) : parameter.label();
    }

    /**
     * Vertical slots for the controls. Paging keeps the seed and reset actions accessible when a recipe declares more
     * parameters than the sidebar can show at once.
     *
     * @param rows parameter rows per page
     * @param start first parameter label
     * @param rowHeight distance between parameter labels
     * @param seedY seed label
     * @param pagerY page controls, when needed
     * @param resetY reset action directly below the seed and any page controls
     * @param cardHeight background height, including bottom padding
     */
    record ControlLayout(int rows, int start, int rowHeight, int seedY, int pagerY, int resetY, int cardHeight) {
        /**
         * Fits one to four parameter rows while reserving the seed, paging, and footer slots.
         *
         * @param height examiner height
         * @param parameters number of declared parameters, from zero to eight
         * @return non-overlapping sidebar slots
         */
        static ControlLayout fit(int height, int parameters) {
            int start = height < 270 ? 52 : 56, rowHeight = height < 270 ? 30 : 36;
            int rows = Math.clamp((height - start - 108) / rowHeight, 1, 4);
            boolean paged = parameters > rows;
            if (paged)
                rows = Math.clamp((height - start - 132) / rowHeight, 1, 4);
            int seed = start + Math.min(parameters, rows) * rowHeight + 10;
            int pager = seed + 60, reset = pager + (paged ? 24 : 0);
            return new ControlLayout(rows, start, rowHeight, seed, pager, reset, reset - 12);
        }
    }

    /**
     * Network-specific state layered on the shared chain/network camera viewport. No worker touches widgets;
     * PreviewPreparation owns cancellation and only its latest result can be taken.
     */
    static final class NetworkViewport extends StructureViewport<NetworkViewport> {
        /** Template source, used to reject stale sessions after registration reload. */
        private final MoleculeDef definition;
        /** Immutable recipe shared by every request in this session. */
        private final PolymerNetworkDefinition network;
        /** Whether this source originally belonged to the live client view. */
        private final boolean registered;
        /** Replaceable worker; requesting a new realization cancels its predecessor. */
        private final PreviewPreparation<Prepared> preparation = new PreviewPreparation<>();
        /** Current controls. A request copies these before submitting work. */
        private final LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        /** Current probability controls, separate from the last successful realization. */
        private final LinkedHashMap<String, Double> probabilities = new LinkedHashMap<>();
        /** Last successful snapshot, retained after pending or rejected requests and used by export. */
        private PolymerPreview successful;
        /** Cached finite geometry; avoid reconstructing it on each draw. */
        private MoleculeStructure geometry;
        /** Current signed seed, separate from editable, potentially invalid input. */
        private long seed;
        /** Editable decimal seed text; applied explicitly so partial typing does not rebuild. */
        private String seedText;
        /** Cached presentation values for the successful model. */
        private String formula = "", mass = "";
        /** Preparation, validation, or export notice drawn by the shared viewport. */
        private String message = "";
        /** Whether the registered source changed while this session was open. */
        private boolean definitionChanged;
        private int page;
        /** Visible controls per page, fitted to the current examiner height. */
        private int rowsPerPage = 4;

        /**
         * One worker result. Failure has no geometry; the caller retains its prior successful snapshot.
         *
         * @param preview successful immutable realization, or null on validation failure
         * @param geometry compiled finite geometry, or null on validation failure
         * @param mass formatted standard-weight mass, or null on failure
         * @param failure validation message, or null on success
         */
        private record Prepared(PolymerPreview preview, MoleculeStructure geometry, String mass, String failure) {
        }

        /**
         * Creates a session using valid recipe defaults and immediately requests its first preview.
         *
         * @param definition template source
         * @param network parsed immutable recipe
         * @param width viewport width
         * @param height viewport height
         */
        NetworkViewport(MoleculeDef definition, PolymerNetworkDefinition network, int width, int height) {
            super(width, height);
            this.definition = definition;
            this.network = network;
            registered = MoleculeStore.get(definition.id()) == definition;
            resetParameters();
        }

        /** Restores declared defaults and seed; camera and pause state remain unchanged. */
        void resetParameters() {
            NetworkPreviewRequest defaults = network.defaults();
            counts.clear();
            counts.putAll(defaults.counts());
            probabilities.clear();
            probabilities.putAll(defaults.probabilities());
            seed = defaults.seed();
            seedText = Long.toString(seed);
            rebuild();
        }
        /**
         * Validates an exact signed 64-bit decimal seed, avoiding floating-point conversion.
         *
         * @param text editable seed text
         */
        void applySeed(String text) {
            try {
                seed = Long.parseLong(text);
                seedText = Long.toString(seed);
                rebuild();
            } catch (NumberFormatException invalid) {
                message = I18n.format("avogadro.network.invalid_seed");
            }
        }
        /** Advances the seed with ordinary 64-bit wraparound, then requests a new realization. */
        void nextSeed() {
            seed++;
            seedText = Long.toString(seed);
            rebuild();
        }
        /**
         * Adjusts one declared control within its own bounds; Shift changes count steps from one to eight.
         *
         * @param index parameter index in declaration order
         * @param direction negative to decrease, positive to increase
         */
        void changeParameter(int index, int direction) {
            var parameter = network.parameters().get(index);
            if (parameter.kind().equals("count"))
                counts.compute(parameter.name(), (key, value) -> Math.clamp(value + direction
                        * (Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT) ? 8 : 1),
                        (int) parameter.min(), (int) parameter.max()));
            else
                probabilities.compute(parameter.name(), (key, value) -> Math
                        .clamp(Math.round((value + direction * .05) * 100) / 100d, parameter.min(), parameter.max()));
            rebuild();
        }
        /**
         * Formats a control without reading geometry from a pending request.
         *
         * @param index declared parameter index
         * @return count or percentage; empty if no parameter occupies that row
         */
        String parameterValue(int index) {
            if (index >= network.parameters().size())
                return "";
            var parameter = network.parameters().get(index);
            return parameter.kind().equals("count")
                    ? counts.get(parameter.name()).toString()
                    : String.format(Locale.ROOT, "%.0f%%", probabilities.get(parameter.name()) * 100);
        }
        /** Copies controls into one request. A failed realization never replaces successful geometry. */
        private void rebuild() {
            if (definitionChanged)
                return;
            NetworkPreviewRequest request = new NetworkPreviewRequest(counts, probabilities, seed);
            message = "";
            preparation.request(() -> {
                try {
                    PolymerPreview preview = network.preview(request);
                    var weight = preview.weight();
                    return new Prepared(preview, preview.structure(),
                            weight == null ? I18n.format("avogadro.network.no_mass") : weight.molarMassText(), null);
                } catch (IllegalArgumentException invalid) {
                    return new Prepared(null, null, null, invalid.getMessage());
                }
            });
        }
        /** Applies the newest result on the client thread and detects replaced registered definitions. */
        @Override
        protected void updatePreview() {
            if (!definitionChanged && registered && MoleculeStore.get(definition.id()) != definition) {
                definitionChanged = true;
                preparation.close();
                message = I18n.format("avogadro.preview.definition_changed");
                return;
            }
            Prepared ready = preparation.take();
            if (ready == null)
                return;
            if (ready.failure() != null) {
                message = ready.failure();
                return;
            }
            successful = ready.preview();
            geometry = ready.geometry();
            formula = ChemicalFormula.display(successful.formula());
            mass = ready.mass();
        }
        /** Returns the cached successful geometry to the shared renderer. */
        @Override
        protected MoleculeStructure geometry() {
            return geometry;
        }
        /** Returns the current preparation or validation notice to the shared viewport. */
        @Override
        protected String status() {
            return preparation.isPending() ? I18n.format("avogadro.preview.preparing") : message;
        }
        /** Cancels owned work when the shared viewport closes. */
        @Override
        protected void closePreview() {
            preparation.close();
        }
        /** Submits exactly the retained successful realization, then reports completion on the client thread. */
        void export() {
            if (successful == null)
                return;
            try {
                AvogadroClientApi.exports()
                        .submit(MediaExportRequest.polymer(successful, MoleculeItems.displayName(definition)).build())
                        .onComplete(result -> {
                            message = result.failure() == null ? result.state().toString() : result.failure();
                        });
                message = I18n.format("avogadro.network.export_started");
            } catch (IOException | IllegalStateException failure) {
                message = failure.getMessage();
            }
        }
    }

}

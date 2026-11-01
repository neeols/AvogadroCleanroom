package io.github.wurtzitane.avogadro.client;

import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;
import io.github.wurtzitane.avogadro.Avogadro;
import io.github.wurtzitane.avogadro.api.ElementCatalog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;

import java.util.HashMap;
import java.util.Map;

/**
 * Manages the interactive periodic table ModularUI screen, session state, and table widget interactions.
 */
public final class PeriodicTableScreen {

    /** Private constructor to prevent instantiation of utility class. */
    private PeriodicTableScreen() {}

    /** Kept only while the table and its atom examiner are open. */
    public static final class Session {
        private final Map<Integer, Integer> selectedIsotopes = new HashMap<>();
        private int selectedAtomicNumber = -1;

        /**
         * Retrieves the currently selected atomic number.
         *
         * @return selected atomic number, or {@code -1} if none selected
         */
        public int selectedAtomicNumber() { return selectedAtomicNumber; }

        /**
         * Creates a copy of this session instance.
         *
         * @return cloned {@link Session} instance
         */
        Session copy() {
            Session copy = new Session();
            copy.selectedAtomicNumber = selectedAtomicNumber;
            copy.selectedIsotopes.putAll(selectedIsotopes);
            return copy;
        }

        /**
         * Retrieves the selected mass number for a given element.
         *
         * @param atomicNumber atomic number
         * @return selected mass number, or reference mass number as default
         */
        public int selectedMassNumber(int atomicNumber) {
            var element = ElementCatalog.byAtomicNumber(atomicNumber);
            if (element == null) throw new IllegalArgumentException("Unknown element");
            return selectedIsotopes.getOrDefault(atomicNumber, element.referenceMassNumber());
        }

        /**
         * Selects a specific isotope mass number for an element.
         *
         * @param atomicNumber atomic number
         * @param massNumber   isotope mass number
         */
        public void selectIsotope(int atomicNumber, int massNumber) {
            var element = ElementCatalog.byAtomicNumber(atomicNumber);
            if (element == null || element.isotopes().stream().noneMatch(i -> i.massNumber() == massNumber))
                throw new IllegalArgumentException("Unknown isotope");
            selectedIsotopes.put(atomicNumber, massNumber);
        }
    }

    /** Opens the periodic table screen with a fresh session. */
    public static void open() { open(new Session()); }

    /**
     * Checks whether the periodic table screen is currently open.
     *
     * @return {@code true} if open, {@code false} otherwise
     */
    public static boolean isOpen() {
        var screen = ModularScreen.getCurrent();
        return screen != null && isAvogadroMenuOwner(screen.getOwner()) && "periodic_table".equals(screen.getName());
    }

    /**
     * Checks whether any Avogadro menu or GUI is currently open.
     *
     * @return {@code true} if an Avogadro menu is open, {@code false} otherwise
     */
    public static boolean isAvogadroMenuOpen() {
        var screen = ModularScreen.getCurrent();
        return (screen != null && isAvogadroMenuOwner(screen.getOwner())) || AvogadroGuiFactory.isOpen();
    }

    /**
     * Checks whether the specified owner string matches the Avogadro mod ID.
     *
     * @param owner owner identifier string
     * @return {@code true} if owner matches, {@code false} otherwise
     */
    public static boolean isAvogadroMenuOwner(String owner) {
        return Avogadro.MODID.equals(owner);
    }

    /**
     * Opens the periodic table screen with a specified session.
     *
     * @param session table session state
     */
    public static void open(Session session) {
        Minecraft client = Minecraft.getMinecraft();
        ViewerWindows.schedule(() -> {
            if (client.player == null) return;
            ViewerWindows.prepareOpen();
            ScaledResolution resolution = ViewerWindows.resolution();
            ViewerWindows.open(createScreen(session, Math.max(1, resolution.getScaledWidth() - 12),
                    Math.max(1, resolution.getScaledHeight() - 12)));
        });
    }

    /**
     * Creates and configures the modular screen for the periodic table.
     *
     * @param session session state
     * @param width   screen width in pixels
     * @param height  screen height in pixels
     * @return constructed {@link ModularScreen} instance
     */
    public static ModularScreen createScreen(Session session, int width, int height) {
        if (session == null) throw new IllegalArgumentException("Table session is required");
        ModularPanel panel = ModularPanel.defaultPanel("periodic_table", width, height)
                .background(StructureStyle.surface(StructureStyle.PANEL, StructureStyle.BORDER));
        panel.child(new TableWidget(session, width, height).size(width, height));
        ModularScreen screen = new ModularScreen("avogadro", panel) {
            @Override public boolean onKeyPressed(char character, int key) {
                if (ViewerWindows.isCloseKey(this, key)) {
                    ViewerWindows.close(this);
                    return true;
                }
                return super.onKeyPressed(character, key);
            }
        }.openParentOnClose(false).pausesGame(false);
        panel.child(StructureStyle.closeButton(() -> ViewerWindows.close(screen)).pos(width - 21, 3));
        ViewerWindows.addButton(panel, () -> open(session.copy()), width - 43, 3);
        return screen;
    }

    /**
     * Interactive table widget handling element cell clicks and rendering within the modular panel.
     */
    public static final class TableWidget extends Widget<TableWidget> implements Interactable {
        private final Session session;
        private final PeriodicTableLayout.Layout layout;

        /**
         * Constructs a new table widget.
         *
         * @param session session state
         * @param width   widget width
         * @param height  widget height
         */
        public TableWidget(Session session, int width, int height) {
            this.session = session;
            layout = PeriodicTableLayout.fit(width, height);
        }

        /**
         * Retrieves the session associated with this widget.
         *
         * @return session instance
         */
        public Session session() { return session; }

        /**
         * Retrieves the periodic table layout configuration.
         *
         * @return layout configuration
         */
        public PeriodicTableLayout.Layout layout() { return layout; }

        /**
         * Determines the element selection at the specified mouse position and button click.
         *
         * @param button mouse button index
         * @param mouseX mouse X coordinate
         * @param mouseY mouse Y coordinate
         * @return atomic number of the selected cell, or {@code -1}
         */
        int selectionAt(int button, int mouseX, int mouseY) {
            return button == 0 ? layout.hit(mouseX, mouseY) : -1;
        }

        /**
         * Renders the table widget and highlights hovered cells.
         *
         * @param context GUI context
         * @param theme   widget theme entry
         */
        @Override public void draw(ModularGuiContext context, WidgetThemeEntry<?> theme) {
            int hovered = context.isHovered(this) ? layout.hit(context.getMouseX(), context.getMouseY()) : -1;
            PeriodicTableRenderer.draw(layout, hovered, session.selectedAtomicNumber);
        }

        /**
         * Selects an element and opens its atom examiner view.
         *
         * @param atomicNumber atomic number to select
         */
        public void select(int atomicNumber) {
            var element = ElementCatalog.byAtomicNumber(atomicNumber);
            if (element == null) return;
            session.selectedAtomicNumber = atomicNumber;
            MoleculeExaminer.openTableElement(element.symbol(), session.selectedMassNumber(atomicNumber),
                    () -> open(session), mass -> session.selectIsotope(atomicNumber, mass));
        }

        /**
         * Handles mouse press events on element cells.
         *
         * @param button mouse button index
         * @return interaction result status
         */
        @Override public Result onMousePressed(int button) {
            if (button != 0) return Result.IGNORE;
            int element = selectionAt(button, getContext().getMouseX(), getContext().getMouseY());
            if (element < 0) return Result.IGNORE;
            Interactable.playButtonClickSound();
            select(element);
            return Result.SUCCESS;
        }
    }
}
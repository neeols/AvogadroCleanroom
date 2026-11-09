package io.github.wurtzitane.avogadro.themecreator;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;

import com.google.gson.JsonParser;

import io.github.wurtzitane.avogadro.theme.ThemeDefinition;
import io.github.wurtzitane.avogadro.theme.ThemeStore;

/**
 * Desktop theme workbench with native menu previews and an optionally detached color editor. The color resolver is
 * shared with Minecraft; previews do not duplicate its menus.
 */
public final class ThemeCreator {
    /** Shared discovery and inheritance rules; UI commands never write the catalog automatically. */
    private final ThemeStore store;
    private final List<PreviewAtlas.Tab> previews;
    private final Map<String, String> roleNames = new LinkedHashMap<>();
    private final Map<String, String> roleGroups = new LinkedHashMap<>();
    /** Current authoring state. Only validated edits enter its undo history. */
    private ThemeDraft draft;
    /** Last successfully opened/saved snapshot, used for dirty checks and comparison swatches. */
    private ThemeDefinition saved;
    private ThemeDefinition.Compiled savedColors;
    /** Null for a new/template copy; otherwise the destination used by Save. */
    private Path currentFile;
    private final JFrame previewWindow = new JFrame("Avogadro Theme Creator — Previews");
    private final JFrame editorWindow = new JFrame("Theme Creator — Colors");
    private final JTabbedPane tabs = new JTabbedPane(JTabbedPane.TOP, JTabbedPane.SCROLL_TAB_LAYOUT);
    private final List<PreviewAtlas.Canvas> canvases = new ArrayList<>();
    private final JCheckBox hover = new JCheckBox("Show hover states");
    private final JLabel status = new JLabel("Click an element in a preview, or choose a palette color to begin.");
    private final JLabel targetLabel = new JLabel("Choose an element or a shared palette color");
    private final JTextField hex = new JTextField(12);
    private final JSlider opacity = new JSlider(0, 255, 255);
    private final JComboBox<String> scope = new JComboBox<>(
            new String[]{"This element", "This type in this menu", "This type in every menu"});
    private final JComboBox<String> roles = new JComboBox<>();
    private final JComboBox<String> groups = new JComboBox<>();
    private final JComboBox<String> menus = new JComboBox<>();
    private final HexColorPicker picker = new HexColorPicker(this::picked, () -> {
        draft.endEdit();
        draft.beginEdit();
    }, () -> draft.endEdit());
    private final Map<String, JButton> paletteButtons = new LinkedHashMap<>();
    /** Coalesces rapid input samples; intermediate drag values need not each recolor a full raster. */
    private final Timer repaintTimer = new Timer(35, event -> refreshPreview());
    private String selectedScreen = "shared";
    private String selectedElement = "root";
    private String selectedRole = "window.background";
    /** Non-null only while editing a shared palette token rather than a semantic property. */
    private String paletteToken = "primary";
    /** Prevents programmatic widget synchronization from being mistaken for a user edit. */
    private boolean refreshing;
    private boolean hexValid = true;
    private boolean detached;
    private javax.swing.JSplitPane workbench;
    private JScrollPane editorScroll;
    private final JTabbedPane editTabs = new JTabbedPane();
    private final JTextField propertySearch = new JTextField();
    private final JTextField pathField = new JTextField();
    private final JCheckBox browseAll = new JCheckBox("Browse all color properties");
    private final JLabel scopeHint = new JLabel();
    private final JLabel hexHint = new JLabel("RGB: 6 digits. ARGB: 8 digits, with opacity first.");
    private final JLabel swatches = new JLabel();
    private final JButton undoButton = button("Undo", () -> {
        draft.undo();
        changed();
    });
    private final JButton redoButton = button("Redo", () -> {
        draft.redo();
        changed();
    });
    private final JButton saveButton = button("Save", () -> save());
    private final JComboBox<String> themeChoices = new JComboBox<>();
    private String templateChoice = "default_theme";
    private JPanel paletteGrid;

    /**
     * Loads the shared theme catalog and initial immutable draft before building Swing controls. All editor state is
     * owned by the event-dispatch thread.
     */
    private ThemeCreator(Path themes, Path initial) throws IOException {
        store = new ThemeStore(themes.getParent());
        previews = PreviewAtlas.read();
        try (var stream = ThemeCreator.class.getResourceAsStream("/assets/avogadro/theme-roles.json")) {
            if (stream == null) {
                throw new IOException("Missing color-role descriptions");
            }
            var values = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            values.entrySet().forEach(entry -> {
                roleNames.put(entry.getKey(), entry.getValue().getAsJsonObject().get("name").getAsString());
                roleGroups.put(entry.getKey(), entry.getValue().getAsJsonObject().get("group").getAsString());
            });
        }
        ThemeDefinition document = ThemeStore.bundled("default_theme.json");
        if (initial != null) {
            try (var reader = Files.newBufferedReader(initial, StandardCharsets.UTF_8)) {
                document = ThemeDefinition.read(reader);
            }
            currentFile = initial;
        }
        draft = new ThemeDraft(document, store.defaults());
        collectCustomRoles(document);
        saved = document;
        savedColors = draft.compiled();
        buildWindows();
        repaintTimer.setRepeats(false);
        refreshEditor();
        refreshPreview();
    }

    /**
     * Starts Theme Creator. The optional themes directory makes Save As open in a Minecraft instance's theme folder.
     *
     * @param arguments optional --themes-directory folder and --theme file
     */
    public static void main(String[] arguments) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < arguments.length; i += 2) {
            if (i + 1 >= arguments.length) {
                throw new IllegalArgumentException("Expected a value after " + arguments[i]);
            }
            options.put(arguments[i], arguments[i + 1]);
        }
        Path themes = Path
                .of(options.getOrDefault("--themes-directory",
                        Path.of(System.getProperty("user.home"), ".avogadro-theme-creator", "themes").toString()))
                .toAbsolutePath();
        Path initial = options.containsKey("--theme") ? Path.of(options.get("--theme")) : null;
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                new ThemeCreator(themes, initial);
            } catch (Exception failure) {
                JOptionPane.showMessageDialog(null, failure.getMessage(), "Cannot open Theme Creator",
                        JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    /**
     * Builds file commands, theme discovery, menu navigation and the docked preview/editor layout. Selection changes
     * update controls without editing the document; closing either window preserves unsaved work.
     */
    private void buildWindows() {
        JPanel preview = new JPanel(new BorderLayout(8, 8));
        preview.setBorder(BorderFactory.createEmptyBorder(10, 10, 8, 10));
        JPanel files = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        files.add(button("New", () -> {
            if (confirmDiscard()) {
                try {
                    load(ThemeStore.bundled("default_theme.json"), null);
                } catch (IOException failure) {
                    error(failure);
                }
            }
        }));
        files.add(button("Open…", this::open));
        files.add(saveButton);
        files.add(button("Save as / Export…", this::export));
        files.add(undoButton);
        files.add(redoButton);
        files.add(button("Detach / dock colors", this::toggleEditor));
        files.add(button("Help", this::showHelp));
        JPanel templates = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        templates.add(new JLabel("Start from a theme"));
        themeChoices.setModel(new javax.swing.DefaultComboBoxModel<>(
                store.ordered(ThemeStore.Order.RAINBOW, true).keySet().toArray(String[]::new)));
        themeChoices.setSelectedItem(currentFile == null ? "default_theme" : null);
        themeChoices.setPreferredSize(new Dimension(180, 28));
        themeChoices.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean selected, boolean focused) {
                var entry = store.entries().get(value);
                var label = (JLabel) super.getListCellRendererComponent(list,
                        entry == null ? "Opened file" : entry.definition().name(), index, selected, focused);
                label.putClientProperty("html.disable", Boolean.TRUE);
                if (entry != null)
                    label.setIcon(themeSwatch(entry.compiled()));
                return label;
            }
        });
        themeChoices.setToolTipText("Starts an editable copy. Open a file when you want Save to update that file.");
        themeChoices.addActionListener(event -> {
            if (refreshing) {
                return;
            }
            String id = (String) themeChoices.getSelectedItem();
            if (id == null) {
                return;
            }
            if (!confirmDiscard()) {
                refreshing = true;
                themeChoices.setSelectedItem(templateChoice);
                refreshing = false;
                return;
            }
            templateChoice = id;
            load(store.entries().get(id).definition(), null);
        });
        templates.add(themeChoices);
        JComboBox<ThemeStore.Order> order = new JComboBox<>(ThemeStore.Order.values());
        boolean[] sortedExplicitly = {false};
        order.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean selected, boolean focused) {
                return super.getListCellRendererComponent(list,
                        capital(value.toString().toLowerCase(java.util.Locale.ROOT)), index, selected, focused);
            }
        });
        order.setToolTipText(
                "Starts with Theme 01 and Theme 02 first. After changing this sort, every theme joins the chosen order.");
        order.addActionListener(event -> {
            sortedExplicitly[0] = true;
            String selected = (String) themeChoices.getSelectedItem();
            refreshing = true;
            themeChoices.setModel(new javax.swing.DefaultComboBoxModel<>(
                    store.ordered((ThemeStore.Order) order.getSelectedItem()).keySet().toArray(String[]::new)));
            themeChoices.setSelectedItem(selected);
            refreshing = false;
        });
        templates.add(order);
        templates.add(button("Refresh themes", () -> {
            store.reload();
            String selected = (String) themeChoices.getSelectedItem();
            refreshing = true;
            themeChoices.setModel(new javax.swing.DefaultComboBoxModel<>(
                    store.ordered((ThemeStore.Order) order.getSelectedItem(), !sortedExplicitly[0]).keySet()
                            .toArray(String[]::new)));
            themeChoices.setSelectedItem(selected);
            refreshing = false;
            status.setText(store.problems().isEmpty()
                    ? "Theme folder refreshed. Your edits are unchanged."
                    : "Some files could not be loaded; their last valid colors were kept.");
        }));
        JPanel headers = new JPanel(new GridLayout(0, 1, 0, 4));
        headers.add(files);
        headers.add(templates);
        preview.add(headers, BorderLayout.NORTH);
        for (var tab : previews) {
            PreviewAtlas.Canvas canvas = new PreviewAtlas.Canvas(tab, this::select);
            canvas.contextMenu(this::colorMenu);
            canvases.add(canvas);
            tabs.addTab(tab.title(), canvas);
            menus.addItem(tab.title());
        }
        tabs.addChangeListener(event -> {
            int index = tabs.getSelectedIndex();
            if (index < 0 || refreshing) {
                return;
            }
            draft.endEdit();
            selectedScreen = previews.get(index).screen();
            selectedElement = "root";
            selectedRole = "window.background";
            if (editTabs.getSelectedIndex() == 1)
                paletteToken = null;
            refreshing = true;
            menus.setSelectedIndex(index);
            refreshing = false;
            filterRoles();
            refreshPreview();
        });
        JPanel previewArea = new JPanel(new BorderLayout(0, 6));
        JPanel navigation = new JPanel(new BorderLayout(8, 0));
        navigation.add(new JLabel("Preview menu"), BorderLayout.WEST);
        navigation.add(menus, BorderLayout.CENTER);
        previewArea.add(navigation, BorderLayout.NORTH);
        previewArea.add(tabs, BorderLayout.CENTER);
        JPanel view = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        view.add(hover);
        hover.addActionListener(event -> refreshPreview());
        view.add(new JLabel("Set zoom"));
        JComboBox<String> zoom = new JComboBox<>(new String[]{"Fit", "100%", "150%", "200%", "300%", "400%"});
        zoom.addActionListener(event -> {
            int index = tabs.getSelectedIndex();
            if (index >= 0)
                canvases.get(index)
                        .zoom(zoom.getSelectedIndex() == 0
                                ? 0
                                : Double.parseDouble(zoom.getSelectedItem().toString().replace("%", "")) / 100);
        });
        view.add(zoom);
        view.add(button("Fit preview", () -> {
            zoom.setSelectedIndex(0);
            int index = tabs.getSelectedIndex();
            if (index >= 0)
                canvases.get(index).zoom(0);
        }));
        previewArea.add(view, BorderLayout.SOUTH);
        editorScroll = new JScrollPane(buildEditor());
        editorScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        editorScroll.getVerticalScrollBar().setUnitIncrement(18);
        editorScroll.setMinimumSize(new Dimension(350, 200));
        editorScroll.setPreferredSize(new Dimension(390, 600));
        workbench = new javax.swing.JSplitPane(javax.swing.JSplitPane.HORIZONTAL_SPLIT, previewArea, editorScroll);
        workbench.setResizeWeight(1);
        workbench.setContinuousLayout(true);
        workbench.setBorder(null);
        preview.add(workbench, BorderLayout.CENTER);
        status.setBorder(BorderFactory.createEmptyBorder(5, 2, 0, 2));
        preview.add(status, BorderLayout.SOUTH);
        previewWindow.setContentPane(preview);
        previewWindow.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        previewWindow.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                if (confirmDiscard()) {
                    previewWindow.dispose();
                    editorWindow.dispose();
                }
            }
        });
        editorWindow.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        editorWindow.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                toggleEditor();
            }
        });
        shortcuts(previewWindow.getRootPane());
        shortcuts(editorWindow.getRootPane());
        Rectangle bounds = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        int width = Math.min(1360, bounds.width - 24), height = Math.min(900, bounds.height - 40);
        previewWindow.setBounds(bounds.x + 12, bounds.y + 20, width, height);
        previewWindow.setMinimumSize(new Dimension(Math.min(900, width), Math.min(600, height)));
        editorWindow.setBounds(bounds.x + Math.max(12, bounds.width - 440), bounds.y + 30, 420, height - 20);
        previewWindow.setVisible(true);
        workbench.setDividerLocation(Math.max(420, width - 410));
        if (!previews.isEmpty())
            selectedScreen = previews.getFirst().screen();
    }

    /**
     * Builds the palette and selected-part workflows. Programmatic control updates are guarded by refreshing so that
     * displaying a color cannot create an undo entry.
     */
    private JPanel buildEditor() {
        JPanel editor = new EditorPanel();
        editor.setLayout(new BoxLayout(editor, BoxLayout.Y_AXIS));
        editor.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        editor.add(help("Choose what to change",
                "Use the shared palette for broad changes, or click a part of a menu preview to edit that part."));
        JPanel palette = new JPanel(new BorderLayout(0, 6));
        paletteGrid = new JPanel(new GridLayout(0, 3, 5, 5));
        rebuildPalette();
        palette.add(paletteGrid, BorderLayout.CENTER);
        palette.add(
                new JLabel("<html>Linked parts follow these colors. Custom part colors keep their own values.</html>"),
                BorderLayout.SOUTH);
        editTabs.addTab("Whole theme", palette);
        JPanel parts = new JPanel();
        parts.setLayout(new BoxLayout(parts, BoxLayout.Y_AXIS));
        menus.addActionListener(event -> {
            if (!refreshing && menus.getSelectedIndex() >= 0)
                tabs.setSelectedIndex(menus.getSelectedIndex());
        });
        parts.add(labeled("Color to change", roles));
        roles.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean selected, boolean focused) {
                return super.getListCellRendererComponent(list,
                        value == null ? "" : roleNames.getOrDefault(value.toString(), value.toString()), index,
                        selected, focused);
            }
        });
        roles.setPrototypeDisplayValue("Normal button background");
        roles.addActionListener(event -> {
            if (!refreshing && roles.getSelectedItem() != null) {
                draft.endEdit();
                selectedRole = (String) roles.getSelectedItem();
                paletteToken = null;
                refreshEditor();
            }
        });
        parts.add(labeled("Apply color to", scope));
        scope.setToolTipText(
                "Choose one part, the same color property throughout this menu, or that property in every menu. Undo reverses a broad edit in one step.");
        scope.addActionListener(event -> {
            if (!refreshing) {
                draft.endEdit();
                paletteToken = null;
                refreshEditor();
            }
        });
        parts.add(scopeHint);
        parts.add(browseAll);
        JPanel advanced = new JPanel();
        advanced.setLayout(new BoxLayout(advanced, BoxLayout.Y_AXIS));
        advanced.setVisible(false);
        groups.addItem("All properties");
        roleGroups.values().stream().distinct().sorted().forEach(groups::addItem);
        groups.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean selected, boolean focused) {
                return super.getListCellRendererComponent(list, capital(String.valueOf(value)), index, selected,
                        focused);
            }
        });
        groups.addActionListener(event -> {
            if (!refreshing)
                filterRoles();
        });
        propertySearch.putClientProperty("JTextField.placeholderText", "Find text, hover, background…");
        propertySearch.getDocument().addDocumentListener(changes(this::filterRoles));
        advanced.add(labeled("Find property", propertySearch));
        advanced.add(labeled("Color group", groups));
        JPanel path = new JPanel(new BorderLayout(4, 0));
        path.add(pathField, BorderLayout.CENTER);
        path.add(button("Use path", this::usePath), BorderLayout.EAST);
        pathField.setToolTipText("Advanced: an exact element path or wildcard selector from the shared theme format.");
        advanced.add(labeled("Element path", path));
        advanced.add(button("View theme JSON…", this::showJson));
        parts.add(advanced);
        browseAll.addActionListener(event -> {
            advanced.setVisible(browseAll.isSelected());
            filterRoles();
            editorScroll.revalidate();
        });
        editTabs.addTab("Selected part", parts);
        editTabs.addChangeListener(event -> {
            if (!refreshing) {
                draft.endEdit();
                paletteToken = editTabs.getSelectedIndex() == 0 ? "primary" : null;
                filterRoles();
            }
        });
        editor.add(editTabs);
        editor.add(gap());
        targetLabel.setFont(targetLabel.getFont().deriveFont(java.awt.Font.BOLD));
        editor.add(targetLabel);
        editor.add(gap());
        editor.add(swatches);
        editor.add(gap());
        editor.add(picker);
        editor.add(gap());
        editor.add(labeled("Hex color", hex));
        editor.add(hexHint);
        hex.setToolTipText(
                "Hex RGB or ARGB; # is optional. Short hex values are expanded. Invalid text leaves the last valid preview intact.");
        hex.getDocument().addDocumentListener(changes(this::hexChanged));
        hex.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent event) {
                draft.beginEdit();
            }

            @Override
            public void focusLost(java.awt.event.FocusEvent event) {
                draft.endEdit();
                if (hexValid)
                    refreshEditor();
            }
        });
        hex.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "revert-color");
        hex.getActionMap().put("revert-color", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                draft.endEdit();
                refreshEditor();
                status.setText("Kept the last valid color.");
            }
        });
        editor.add(labeled("Opacity", opacity));
        opacity.addChangeListener(event -> {
            if (refreshing) {
                return;
            }
            if (opacity.getValueIsAdjusting())
                draft.beginEdit();
            try {
                picked(opacity.getValue() << 24 | ThemeFiles.color(hex.getText()) & 0xffffff);
            } catch (IllegalArgumentException ignored) {
            }
            if (!opacity.getValueIsAdjusting())
                draft.endEdit();
        });
        JPanel clipboard = new JPanel(new GridLayout(0, 2, 5, 5));
        clipboard.add(button("Copy color", this::copy));
        JButton paste = new JButton("Paste color ▾");
        paste.addActionListener(event -> pasteMenu(paste));
        clipboard.add(paste);
        clipboard.add(button("Cut / reset", this::cut));
        clipboard.add(button("Restore inherited", () -> {
            draft.endEdit();
            draft.reset(target());
            changed();
        }));
        clipboard.add(button("Link to palette…", this::linkPalette));
        editor.add(gap());
        editor.add(clipboard);
        editor.add(new JLabel(
                "<html>Right-click a preview part for color actions.<br>Save as / Export creates the JSON file for Minecraft.</html>"));
        filterRoles();
        return editor;
    }

    /**
     * Resolves the current palette token or element/menu/global editing scope. The same target is used by the picker,
     * hex field, reset and clipboard commands.
     */
    private ThemeDraft.Target target() {
        if (paletteToken != null) {
            return new ThemeDraft.Target(ThemeDraft.Scope.PALETTE, "*", "*", paletteToken);
        }
        ThemeDraft.Scope selected = switch (scope.getSelectedIndex()) {
            case 1 -> ThemeDraft.Scope.MENU;
            case 2 -> ThemeDraft.Scope.EVERYWHERE;
            default -> ThemeDraft.Scope.ELEMENT;
        };
        return new ThemeDraft.Target(selected, selectedScreen, selectedElement, selectedRole);
    }

    /**
     * Runs an editor refresh for every kind of text-document change. The receiving action must ignore programmatic
     * updates while refreshing is true.
     */
    private static DocumentListener changes(Runnable action) {
        return new DocumentListener() {
            public void insertUpdate(DocumentEvent event) {
                action.run();
            }

            public void removeUpdate(DocumentEvent event) {
                action.run();
            }

            public void changedUpdate(DocumentEvent event) {
                action.run();
            }
        };
    }

    /**
     * Moves the existing editor component between the split pane and its secondary window. It never copies the draft or
     * creates another set of listeners.
     */
    private void toggleEditor() {
        if (detached) {
            editorWindow.setVisible(false);
            editorWindow.setContentPane(new JPanel());
            workbench.setRightComponent(editorScroll);
            workbench.setDividerLocation(Math.max(350, workbench.getWidth() - 400));
        } else {
            workbench.setRightComponent(null);
            editorWindow.setContentPane(editorScroll);
            editorWindow.setVisible(true);
            editorWindow.toFront();
        }
        detached = !detached;
        workbench.revalidate();
    }

    /**
     * Shows representative resolved surface, text and button colors beside a theme name, including inherited palette
     * values.
     */
    private static javax.swing.Icon themeSwatch(ThemeDefinition.Compiled theme) {
        int[] colors = {theme.paletteColor("primary"), theme.paletteColor("secondary"), theme.paletteColor("tertiary"),
                theme.paletteColor("ink")};
        return new javax.swing.Icon() {
            public int getIconWidth() {
                return 54;
            }

            public int getIconHeight() {
                return 18;
            }

            public void paintIcon(java.awt.Component component, java.awt.Graphics graphics, int x, int y) {
                for (int i = 0; i < colors.length; i++) {
                    graphics.setColor(new Color(colors[i], true));
                    graphics.fillRect(x + i * 13, y + 2, 12, 14);
                }
            }
        };
    }

    /**
     * Explains the intended use of common palette tokens; custom tokens remain valid without a built-in description.
     */
    private String paletteHelp(String token) {
        return switch (token) {
            case "primary" -> "Primary: main window surfaces in the bundled themes.";
            case "secondary" -> "Secondary: panels and model viewports.";
            case "tertiary" -> "Tertiary: buttons and raised surfaces.";
            case "quaternary" -> "Quaternary: selected buttons and controls.";
            case "ink" -> "Main text. Check that it contrasts with the surrounding surfaces.";
            case "muted" -> "Secondary text and less prominent labels.";
            default -> "Shared palette color $" + token + ". Linked properties change together.";
        };
    }

    /**
     * Validates an advanced element selector before making it the editing target. Choosing a path alone leaves the
     * theme unchanged.
     */
    private void usePath() {
        try {
            String value = pathField.getText().trim();
            new ThemeDefinition.Rule(selectedScreen, List.of(value), Map.of());
            draft.endEdit();
            selectedElement = value;
            paletteToken = null;
            scope.setSelectedIndex(0);
            refreshEditor();
            status.setText("Using the custom element path. Its color will change when you edit a color below.");
        } catch (IllegalArgumentException invalid) {
            error(invalid);
        }
    }

    /**
     * Stores a palette reference at the current target, so subsequent palette edits propagate to that part. Cancelling
     * the chooser leaves the draft intact.
     */
    private void linkPalette() {
        if (paletteToken != null) {
            status.setText("Select a menu part first to link it to a shared palette color.");
            return;
        }
        Object token = JOptionPane.showInputDialog(previewWindow,
                "This part will follow the chosen palette color, including later palette edits.",
                "Link to shared palette", JOptionPane.PLAIN_MESSAGE, null, paletteTokens().toArray(), "primary");
        if (token != null) {
            draft.endEdit();
            draft.set(target(), "$" + token);
            changed();
            status.setText("Linked this color to $" + token + ". Undo reverses the link.");
        }
    }

    /**
     * Displays a read-only serialization of the current draft for inspection and copying. This does not save or replace
     * the document.
     */
    private void showJson() {
        try {
            var writer = new java.io.StringWriter();
            draft.document().write(writer);
            var text = new javax.swing.JTextArea(writer.toString());
            text.setEditable(false);
            text.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
            text.setCaretPosition(0);
            var scroll = new JScrollPane(text);
            scroll.setPreferredSize(new Dimension(680, 480));
            JOptionPane.showMessageDialog(previewWindow, scroll, "Current theme JSON — select text to copy it",
                    JOptionPane.PLAIN_MESSAGE);
        } catch (IOException failure) {
            error(failure);
        }
    }

    /**
     * Explains the main editing workflow, scoped commands and keyboard shortcuts without requiring knowledge of the
     * JSON schema.
     */
    private void showHelp() {
        JOptionPane.showMessageDialog(previewWindow,
                "1. Start from a theme, or Open a JSON file.\n2. Change the Whole theme palette, or click a preview part.\n3. Choose how widely to apply its color, then pick a color.\n4. Save as / Export gives your theme a name and a JSON file.\n\n"
                        + "Mouse wheel: zoom. Middle drag: pan. Fit preview resets both.\nCopy / Cut / Paste color: Ctrl+C / Ctrl+X / Ctrl+V.\nChoose a paste scope: Ctrl+Shift+V. Undo / redo: Ctrl+Z / Ctrl+Y.\nSave: Ctrl+S. Save as: Ctrl+Shift+S. Open: Ctrl+O.\n\n"
                        + "Start from creates a copy; it never overwrites a bundled theme.\nRestore inherited removes the custom color in the chosen scope.\nBrowse all color properties exposes advanced roles and element paths.\nThe color editor can be docked or detached. Closing it docks it again.\n\n"
                        + "Previews use captured Minecraft layouts and the same color resolver.\nCheck transparent colors and moving particles in Minecraft before sharing.",
                "Theme Creator — quick help", JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * Creates commands for the part selected by the preview click. Each invocation reads the current target rather than
     * retaining an obsolete selection.
     */
    private JPopupMenu colorMenu() {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem copy = new JMenuItem("Copy this color");
        copy.addActionListener(event -> copy());
        menu.add(copy);
        JMenuItem cut = new JMenuItem("Cut color and restore inherited");
        cut.addActionListener(event -> cut());
        menu.add(cut);
        menu.addSeparator();
        addPaste(menu, "Paste to this part", ThemeDraft.Scope.ELEMENT);
        addPaste(menu, "Paste to matching parts in this menu", ThemeDraft.Scope.MENU);
        addPaste(menu, "Paste to matching parts in every menu", ThemeDraft.Scope.EVERYWHERE);
        return menu;
    }

    /**
     * Turns a native mask hit into an element/property target, synchronizes menu navigation and opens the selected-part
     * workflow. Selection itself is not an edit.
     */
    private void select(PreviewAtlas.Slot slot) {
        draft.endEdit();
        refreshing = true;
        selectedScreen = slot.screen();
        selectedElement = slot.element();
        selectedRole = slot.role();
        paletteToken = null;
        editTabs.setSelectedIndex(1);
        scope.setSelectedIndex(0);
        propertySearch.setText("");
        groups.setSelectedItem("All properties");
        refreshing = false;
        filterRoles();
        if (detached)
            editorWindow.toFront();
        status.setText("Selected " + roleNames.getOrDefault(selectedRole, selectedRole)
                + ". Choose its color and editing scope.");
        status.setToolTipText(selectedScreen + " / " + selectedElement);
    }

    /** Contextual properties are the default; advanced search includes the complete shared role catalog. */
    private void filterRoles() {
        if (refreshing) {
            return;
        }
        refreshing = true;
        try {
            var visible = new java.util.LinkedHashSet<String>();
            int tab = tabs.getSelectedIndex();
            if (!browseAll.isSelected() && paletteToken == null && tab >= 0) {
                for (var slot : previews.get(tab).slots())
                    if (slot.element().equals(selectedElement) || slot.element().startsWith(selectedElement + "/"))
                        visible.add(slot.role());
                visible.add(selectedRole);
            } else
                visible.addAll(roleNames.keySet());
            String group = String.valueOf(groups.getSelectedItem());
            String query = propertySearch.getText().trim().toLowerCase(java.util.Locale.ROOT);
            var options = visible.stream().filter(role -> roleNames.containsKey(role))
                    .filter(role -> !browseAll.isSelected() || "All properties".equals(group)
                            || group.equals(roleGroups.get(role)))
                    .filter(role -> !browseAll.isSelected() || query.isEmpty()
                            || role.toLowerCase(java.util.Locale.ROOT).contains(query)
                            || roleNames.get(role).toLowerCase(java.util.Locale.ROOT).contains(query))
                    .toList();
            roles.setModel(new javax.swing.DefaultComboBoxModel<>(options.toArray(String[]::new)));
            if (options.contains(selectedRole))
                roles.setSelectedItem(selectedRole);
            else if (!options.isEmpty())
                selectedRole = options.getFirst();
        } finally {
            refreshing = false;
        }
        refreshEditor();
    }

    /**
     * Combines inherited and authored palette names in stable order, including custom tokens from user themes.
     */
    private java.util.Set<String> paletteTokens() {
        var names = new java.util.LinkedHashSet<>(store.defaults().palette().keySet());
        names.addAll(draft.document().palette().keySet());
        return names;
    }

    /**
     * Rebuilds palette buttons after loading a document so custom tokens are selectable. Button colors are refreshed
     * from the compiled draft.
     */
    private void rebuildPalette() {
        paletteGrid.removeAll();
        paletteButtons.clear();
        for (String token : paletteTokens()) {
            JButton color = button(capital(token), () -> {
                draft.endEdit();
                paletteToken = token;
                refreshEditor();
            });
            color.setToolTipText(paletteHelp(token));
            paletteButtons.put(token, color);
            paletteGrid.add(color);
        }
        paletteGrid.revalidate();
        paletteGrid.repaint();
    }

    /**
     * Returns the effective ARGB value after inheritance and selector precedence; this is also the color copied to the
     * clipboard.
     */
    private int selectedColor() {
        if (paletteToken != null) {
            Integer color = draft.compiled().paletteColor(paletteToken);
            return color == null ? 0xff000000 : color;
        }
        return draft.compiled().color(selectedRole, selectedScreen, selectedElement, 0xff000000);
    }

    /**
     * Synchronizes widgets and comparison swatches from the current draft. Suppressing change listeners here prevents
     * recursive edits and accidental undo steps.
     */
    private void refreshEditor() {
        refreshing = true;
        try {
            int color = selectedColor();
            hex.setText(ThemeDefinition.hex(color));
            hexValid = true;
            hexHint.setText("RGB: 6 digits. ARGB: 8 digits, with opacity first.");
            boolean editable = paletteToken != null || roles.getItemCount() > 0;
            picker.setEnabled(editable);
            hex.setEnabled(editable);
            roles.setEnabled(roles.getItemCount() > 0);
            scope.setEnabled(paletteToken == null);
            pathField.setText(selectedElement);
            scopeHint.setText("<html>"
                    + (scope.getSelectedIndex() == 0
                            ? "Changes the selected part and its nested parts."
                            : scope.getSelectedIndex() == 1
                                    ? "Changes this property throughout the current menu, including customized parts."
                                    : "Changes this property in every menu, including customized parts.")
                    + " Undo reverses the whole edit.</html>");
            var original = savedColors;
            Integer prior = paletteToken == null
                    ? original.color(selectedRole, selectedScreen, selectedElement, color)
                    : original.paletteColor(paletteToken);
            swatches.setIcon(colorComparison(prior == null ? color : prior, color));
            swatches.setText("Saved                         Now");
            swatches.setHorizontalTextPosition(JLabel.CENTER);
            swatches.setVerticalTextPosition(JLabel.BOTTOM);
            hex.setForeground(UIManager.getColor("TextField.foreground"));
            picker.color(color);
            opacity.setValue(color >>> 24);
            opacity.setEnabled(editable && (paletteToken != null || !selectedRole.startsWith("atom.")));
            opacity.setToolTipText(selectedRole.startsWith("atom.")
                    ? "Particle opacity belongs to the atom viewer settings; themes change particle colors."
                    : "Opacity is the first two digits in #AARRGGBB. Opaque RGB colors are easiest to share.");
            if (roles.getItemCount() > 0)
                roles.setSelectedItem(selectedRole);
            String title = paletteToken == null
                    ? "Selected: " + roleNames.getOrDefault(selectedRole, selectedRole)
                    : "Shared palette: " + capital(paletteToken);
            targetLabel.setText("<html><div style='width:275px'>"
                    + title.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</div></html>");
            targetLabel.setToolTipText(paletteToken == null
                    ? selectedScreen + " / " + selectedElement
                    : "Properties linked with $" + paletteToken + " change together.");
            for (var entry : paletteButtons.entrySet()) {
                Integer swatch = draft.compiled().paletteColor(entry.getKey());
                if (swatch != null) {
                    final int ink = swatch;
                    entry.getValue().setIcon(new javax.swing.Icon() {
                        public int getIconWidth() {
                            return 18;
                        }

                        public int getIconHeight() {
                            return 18;
                        }

                        public void paintIcon(java.awt.Component c, java.awt.Graphics g, int x, int y) {
                            g.setColor(new Color(ink, true));
                            g.fillRect(x + 1, y + 1, 16, 16);
                            g.setColor(Color.GRAY);
                            g.drawRect(x, y, 17, 17);
                        }
                    });
                }
            }
        } finally {
            refreshing = false;
        }
        undoButton.setEnabled(draft.canUndo());
        redoButton.setEnabled(draft.canRedo());
        saveButton.setEnabled(hexValid && (currentFile == null || !draft.document().equals(saved)));
    }

    /**
     * Applies only complete, valid hex input. Incomplete typing retains the previous preview, and one focused editing
     * gesture becomes one undo step.
     */
    private void hexChanged() {
        if (refreshing) {
            return;
        }
        try {
            int color = ThemeFiles.color(hex.getText());
            hexValid = true;
            hexHint.setText("Color applied to the preview.");
            if (hex.hasFocus())
                draft.beginEdit();
            draft.set(target(), ThemeDefinition.hex(color));
            hex.setForeground(UIManager.getColor("TextField.foreground"));
            picker.color(color);
            refreshing = true;
            opacity.setValue(color >>> 24);
            refreshing = false;
            repaintTimer.restart();
        } catch (IllegalArgumentException invalid) {
            hexValid = false;
            hexHint.setText("Finish the hex value, or press Esc to keep the last color.");
            hex.setForeground(new Color(0xb23b35));
        }
        undoButton.setEnabled(draft.canUndo());
        redoButton.setEnabled(draft.canRedo());
        saveButton.setEnabled(hexValid && (currentFile == null || !draft.document().equals(saved)));
    }

    /**
     * Applies a picker or opacity value at the current target, then updates the hex field and schedules a coalesced
     * preview repaint.
     */
    private void picked(int color) {
        draft.set(target(), ThemeDefinition.hex(color));
        refreshEditor();
        repaintTimer.restart();
    }

    /**
     * Refreshes editor state after a document command and coalesces expensive preview recoloring through the repaint
     * timer.
     */
    private void changed() {
        refreshEditor();
        repaintTimer.restart();
    }

    /**
     * Recolors only the visible preview and updates the unsaved marker. Camera changes and selections remain separate
     * from the authoring document.
     */
    private void refreshPreview() {
        int index = tabs.getSelectedIndex();
        if (index >= 0) {
            canvases.get(index).selection(paletteToken == null ? selectedElement : null);
            canvases.get(index).refresh(draft, hover.isSelected());
        }
        previewWindow.setTitle(
                "Avogadro Theme Creator — " + draft.document().name() + (draft.document().equals(saved) ? "" : " *"));
    }

    /**
     * Copies the resolved color as normalized hex without changing the theme or its undo history.
     */
    private void copy() {
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(ThemeDefinition.hex(selectedColor())), null);
        status.setText("Color copied: " + ThemeDefinition.hex(selectedColor()));
    }

    /**
     * Builds saved/current swatches over a checkerboard so opacity changes remain visible on both light and dark
     * colors.
     */
    private static javax.swing.Icon colorComparison(int before, int after) {
        return new javax.swing.Icon() {
            public int getIconWidth() {
                return 210;
            }

            public int getIconHeight() {
                return 30;
            }

            public void paintIcon(java.awt.Component component, java.awt.Graphics graphics, int x, int y) {
                for (int column = 0; column < 2; column++) {
                    int left = x + column * 110;
                    for (int row = 0; row < 4; row++)
                        for (int tile = 0; tile < 13; tile++) {
                            graphics.setColor((row + tile) % 2 == 0 ? Color.WHITE : new Color(0xcccccc));
                            graphics.fillRect(left + tile * 8, y + row * 8, 8, 8);
                        }
                    graphics.setColor(new Color(column == 0 ? before : after, true));
                    graphics.fillRect(left, y, 100, 28);
                    graphics.setColor(Color.GRAY);
                    graphics.drawRect(left, y, 100, 28);
                }
            }
        };
    }

    /**
     * Copies the effective color before removing its override in the chosen scope. Resetting restores inheritance
     * rather than guessing a replacement color.
     */
    private void cut() {
        copy();
        draft.endEdit();
        draft.reset(target());
        changed();
    }

    /**
     * Normalizes a clipboard hex value and applies it to the current or explicitly chosen scope as one undoable edit.
     * Invalid clipboard contents leave the draft unchanged.
     */
    private void paste(ThemeDraft.Scope destination) {
        try {
            String text = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor).toString()
                    .trim();
            String color = ThemeDefinition.hex(ThemeFiles.color(text));
            ThemeDraft.Target target = target();
            if (destination != null)
                target = new ThemeDraft.Target(destination, selectedScreen, selectedElement, selectedRole);
            draft.endEdit();
            draft.set(target, color);
            changed();
            status.setText("Pasted " + color + " — "
                    + (destination == null ? "current target" : destination.toString().toLowerCase()));
        } catch (Exception failure) {
            JOptionPane.showMessageDialog(previewWindow, "Copy a hex color such as #82C3F8 first.",
                    "Cannot paste color", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    /**
     * Offers destinations appropriate to the current workflow. Palette editing pastes into the token; part editing can
     * target one part, one menu or all menus.
     */
    private void pasteMenu(JComponent anchor) {
        JPopupMenu menu = new JPopupMenu();
        if (paletteToken != null) {
            JMenuItem item = new JMenuItem("Paste to shared palette color");
            item.addActionListener(event -> paste(null));
            menu.add(item);
        } else {
            addPaste(menu, "This element", ThemeDraft.Scope.ELEMENT);
            addPaste(menu, "All of this type in this menu", ThemeDraft.Scope.MENU);
            addPaste(menu, "All of this type in every menu", ThemeDraft.Scope.EVERYWHERE);
        }
        JComponent destination = anchor == null ? hex : anchor;
        menu.show(destination, 0, destination.getHeight());
    }

    /**
     * Binds one menu item to an explicit destination scope; the actual color is read when the command runs.
     */
    private void addPaste(JPopupMenu menu, String label, ThemeDraft.Scope scope) {
        JMenuItem item = new JMenuItem(label);
        item.addActionListener(event -> paste(scope));
        menu.add(item);
    }

    /**
     * Installs window-level file and color commands for both docked and detached workflows. Focused text controls
     * retain their ordinary text-editing bindings.
     */
    private void shortcuts(JComponent root) {
        int modifier = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        bind(root, KeyEvent.VK_C, modifier, "copy-color", this::copy);
        bind(root, KeyEvent.VK_X, modifier, "cut-color", this::cut);
        bind(root, KeyEvent.VK_V, modifier, "paste-color", () -> paste(null));
        bind(root, KeyEvent.VK_V, modifier | KeyEvent.SHIFT_DOWN_MASK, "paste-scope", () -> pasteMenu(null));
        bind(root, KeyEvent.VK_Z, modifier, "undo-theme", () -> {
            draft.undo();
            changed();
        });
        bind(root, KeyEvent.VK_Y, modifier, "redo-theme", () -> {
            draft.redo();
            changed();
        });
        bind(root, KeyEvent.VK_S, modifier, "save-theme", () -> save());
        bind(root, KeyEvent.VK_S, modifier | KeyEvent.SHIFT_DOWN_MASK, "export-theme", this::export);
        bind(root, KeyEvent.VK_O, modifier, "open-theme", this::open);
    }

    /**
     * Registers a named action on the root pane so the same command works while any non-text child control has focus.
     */
    private static void bind(JComponent root, int key, int modifiers, String id, Runnable action) {
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key, modifiers), id);
        root.getActionMap().put(id, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                action.run();
            }
        });
    }

    /**
     * Offers to save unsaved work before choosing and reading another UTF-8 theme file. Parse failures retain the
     * current document.
     */
    private void open() {
        if (!confirmDiscard()) {
            return;
        }
        JFileChooser chooser = new JFileChooser(store.themesDirectory().toFile());
        chooser.setFileFilter(new FileNameExtensionFilter("Avogadro theme (*.json)", "json"));
        if (chooser.showOpenDialog(previewWindow) == JFileChooser.APPROVE_OPTION) {
            Path file = chooser.getSelectedFile().toPath();
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                load(ThemeDefinition.read(reader), file);
            } catch (Exception failure) {
                error(failure);
            }
        }
    }

    /**
     * Validates a replacement draft before installing it, then rebuilds custom controls and clears the previous
     * selection. A null file denotes a new copy rather than an overwrite target.
     */
    private void load(ThemeDefinition document, Path file) {
        try {
            var loaded = new ThemeDraft(document, store.defaults());
            draft = loaded;
            saved = document;
            savedColors = draft.compiled();
            currentFile = file;
            rebuildPalette();
            collectCustomRoles(document);
            paletteToken = "primary";
            selectedElement = "root";
            refreshing = true;
            groups.removeAllItems();
            groups.addItem("All properties");
            roleGroups.values().stream().distinct().sorted().forEach(groups::addItem);
            editTabs.setSelectedIndex(0);
            propertySearch.setText("");
            if (file != null) {
                themeChoices.setSelectedItem(null);
                templateChoice = null;
            }
            refreshing = false;
            filterRoles();
            changed();
            status.setText(file == null
                    ? "Editing a new copy. Save as / Export chooses its name and file."
                    : "Opened " + file.toAbsolutePath());
        } catch (Exception failure) {
            error(failure);
        }
    }

    /**
     * Adds authored global and rule-local property names to the advanced catalog, keeping unfamiliar extension roles
     * editable.
     */
    private void collectCustomRoles(ThemeDefinition document) {
        var names = new java.util.LinkedHashSet<>(document.colors().keySet());
        document.rules().forEach(rule -> names.addAll(rule.colors().keySet()));
        for (String role : names) {
            if (!roleNames.containsKey(role)) {
                roleNames.put(role, role);
                roleGroups.put(role, "custom");
            }
        }
    }

    /**
     * Writes the current file, or delegates to Save As for a new copy. Saved-state bookkeeping changes only after a
     * successful write; false cancels the caller’s pending operation.
     */
    private boolean save() {
        if (!hexValid) {
            invalidColor();
            return false;
        }
        draft.endEdit();
        if (currentFile == null) {
            return export();
        }
        try {
            ThemeFiles.write(currentFile, draft.document());
            saved = draft.document();
            savedColors = draft.compiled();
            changed();
            status.setText("Saved " + currentFile.toAbsolutePath());
            return true;
        } catch (Exception failure) {
            error(failure);
            return false;
        }
    }

    /**
     * Collects a destination and display metadata, validates the proposed document, then writes it before updating the
     * draft. Cancelling or failing any step preserves the current file and saved state.
     */
    private boolean export() {
        if (!hexValid) {
            invalidColor();
            return false;
        }
        draft.endEdit();
        JFileChooser chooser = new JFileChooser(currentFile == null
                ? store.themesDirectory().toFile()
                : currentFile.toAbsolutePath().getParent().toFile());
        chooser.setDialogTitle("Save Avogadro theme");
        chooser.setFileFilter(new FileNameExtensionFilter("Avogadro theme (*.json)", "json"));
        chooser.setSelectedFile(currentFile == null
                ? store.themesDirectory()
                        .resolve(draft.document().name().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_-]+",
                                "_") + ".json")
                        .toFile()
                : currentFile.toFile());
        JTextField name = new JTextField(draft.document().name(), 18);
        JTextField description = new JTextField(draft.document().description(), 18);
        JPanel metadata = new JPanel();
        metadata.setLayout(new BoxLayout(metadata, BoxLayout.Y_AXIS));
        metadata.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 0));
        metadata.add(new JLabel("Name shown in Minecraft"));
        metadata.add(name);
        metadata.add(gap());
        metadata.add(new JLabel("Short description (optional)"));
        metadata.add(description);
        metadata.add(help("Installing your theme",
                "Save to this instance's themes folder, then reopen Avogadro's theme picker. You can also save elsewhere to share the JSON file."));
        chooser.setAccessory(metadata);
        if (chooser.showSaveDialog(previewWindow) != JFileChooser.APPROVE_OPTION) {
            return false;
        }
        Path file = chooser.getSelectedFile().toPath();
        if (!file.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".json"))
            file = file.resolveSibling(file.getFileName() + ".json");
        if (Files.exists(file) && JOptionPane.showConfirmDialog(previewWindow, "Replace " + file.getFileName() + "?",
                "Save theme", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION)
            return false;
        try {
            var proposed = new ThemeDefinition(name.getText().trim(), description.getText().trim(),
                    draft.document().palette(), draft.document().colors(), draft.document().rules());
            proposed.compile(store.defaults());
            ThemeFiles.write(file, proposed);
            draft.metadata(proposed.name(), proposed.description());
            currentFile = file;
            saved = draft.document();
            savedColors = draft.compiled();
            changed();
            status.setText("Saved " + file.toAbsolutePath() + ". Reopen the Minecraft theme picker to select it.");
            return true;
        } catch (Exception failure) {
            error(failure);
            return false;
        }
    }

    /**
     * Explains why saving is blocked by incomplete hex input and returns focus to the field without discarding the last
     * valid color.
     */
    private void invalidColor() {
        JOptionPane.showMessageDialog(previewWindow,
                "Finish the hex color before saving, or press Esc in the field to keep the last valid color.",
                "Unfinished color", JOptionPane.INFORMATION_MESSAGE);
        hex.requestFocusInWindow();
    }

    /**
     * Returns whether a close/load operation may continue. Save failure or cancellation vetoes the operation; only an
     * explicit Discard abandons changes.
     */
    private boolean confirmDiscard() {
        draft.endEdit();
        if (draft.document().equals(saved) && hexValid) {
            return true;
        }
        int answer = JOptionPane.showOptionDialog(previewWindow, "Save your changes before continuing?",
                "Unsaved theme", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE, null,
                new String[]{"Save", "Discard", "Cancel"}, "Save");
        return answer == JOptionPane.NO_OPTION || answer == JOptionPane.YES_OPTION && save();
    }

    /**
     * Shows an operation failure while keeping the draft available for correction or another save destination.
     */
    private void error(Exception failure) {
        JOptionPane.showMessageDialog(previewWindow, failure.getMessage(), "Theme Creator", JOptionPane.ERROR_MESSAGE);
    }

    /**
     * Creates a command button that delegates to the same action used by menus and shortcuts.
     */
    private static JButton button(String text, Runnable action) {
        JButton button = new JButton(text);
        button.addActionListener(event -> action.run());
        return button;
    }

    /**
     * Pairs a caption with its control for keyboard/accessibility navigation and bounds the row height in the scrolling
     * editor.
     */
    private static JPanel labeled(String label, JComponent component) {
        JPanel row = new JPanel(new BorderLayout(8, 4));
        row.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        JLabel caption = new JLabel(label);
        caption.setLabelFor(component);
        row.add(caption, BorderLayout.WEST);
        row.add(component, BorderLayout.CENTER);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        return row;
    }

    /**
     * Creates a wrapping guidance block sized for the narrow editor pane.
     */
    private static JPanel help(String title, String text) {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(6, 0, 10, 0));
        panel.add(new JLabel("<html><b>" + title + "</b></html>"), BorderLayout.NORTH);
        panel.add(new JLabel("<html><div style='width:280px'>" + text + "</div></html>"), BorderLayout.CENTER);
        return panel;
    }

    /**
     * Adds fixed vertical breathing room without allowing BoxLayout to stretch the separator.
     */
    private static JPanel gap() {
        JPanel panel = new JPanel();
        panel.setPreferredSize(new Dimension(1, 8));
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 8));
        return panel;
    }

    /**
     * Capitalizes an existing nonempty catalog label for display without changing its stored identifier.
     */
    private static String capital(String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    /**
     * Estimates perceived brightness for choosing readable swatch text; alpha is deliberately excluded from this
     * display-only heuristic.
     */
    private static int luminance(int argb) {
        return (299 * (argb >> 16 & 255) + 587 * (argb >> 8 & 255) + 114 * (argb & 255)) / 1000;
    }
    /** Tracks the color editor's viewport width so long labels wrap instead of hiding controls horizontally. */
    private static final class EditorPanel extends JPanel implements javax.swing.Scrollable {
        public Dimension getPreferredScrollableViewportSize() {
            return new Dimension(380, 650);
        }

        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return 18;
        }

        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(18, visible.height - 24);
        }

        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

}

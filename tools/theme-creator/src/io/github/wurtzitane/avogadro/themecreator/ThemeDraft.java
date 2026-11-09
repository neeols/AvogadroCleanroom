package io.github.wurtzitane.avogadro.themecreator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.wurtzitane.avogadro.theme.ThemeDefinition;

/**
 * Undoable authoring state for Theme Creator. One bulk edit is one undo step, regardless of how many controls it
 * affects. The preview and exported document always use the same current state.
 */
public final class ThemeDraft {
    /** Editing scope; a role identifies one property such as a button's background. */
    public enum Scope {
        PALETTE, EVERYWHERE, MENU, ELEMENT
    }

    /**
     * The destination of a color edit.
     *
     * @param scope palette token, all menus, one menu, or one control
     * @param screen menu identifier; ignored for palette/global changes
     * @param element control path; only used for ELEMENT
     * @param role semantic property, or palette token for PALETTE
     */
    public record Target(Scope scope, String screen, String element, String role) {
    }

    private final ThemeDefinition defaults;
    private ThemeDefinition document;
    private ThemeDefinition.Compiled compiled;
    private final Deque<ThemeDefinition> undo = new ArrayDeque<>();
    private final Deque<ThemeDefinition> redo = new ArrayDeque<>();
    /** Changes only when the effective document changes, invalidating painted preview caches. */
    private long revision;
    /** Snapshot before the current drag/focused edit; null between gestures. */
    private ThemeDefinition gestureStart;
    /** The gesture enters history only after its first valid change. */
    private boolean gestureRecorded;

    /** Groups a drag or a focused hex edit into one undo step while previews still update immediately. */
    public void beginEdit() {
        if (gestureStart == null) {
            gestureStart = document;
            gestureRecorded = false;
        }
    }

    /** Ends the current editing gesture. Safe to call before a command, save, or target change. */
    public void endEdit() {
        if (gestureRecorded && document.equals(gestureStart) && !undo.isEmpty() && undo.peek().equals(gestureStart))
            undo.pop();
        gestureStart = null;
        gestureRecorded = false;
    }

    /** Creates a draft; the supplied definitions remain immutable. */
    public ThemeDraft(ThemeDefinition document, ThemeDefinition defaults) {
        this.defaults = defaults;
        this.document = document;
        compiled = document.compile(defaults);
    }

    /** Returns the document that will be exported. */
    public ThemeDefinition document() {
        return document;
    }

    /** Returns resolved colors for the current preview. */
    public ThemeDefinition.Compiled compiled() {
        return compiled;
    }

    /** Returns the revision used for dirty-only preview updates. */
    public long revision() {
        return revision;
    }

    /** Returns whether undo is available. */
    public boolean canUndo() {
        return !undo.isEmpty();
    }

    /** Returns whether redo is available. */
    public boolean canRedo() {
        return !redo.isEmpty();
    }

    /**
     * Applies one color or palette reference. Bulk edits remove narrower overrides of that same property in the
     * selected scope, so "all buttons" also updates previously customized buttons. Other properties and menus keep
     * their overrides.
     *
     * @param target destination and property
     * @param expression hex color or $palette reference
     */
    public void set(Target target, String expression) {
        Map<String, String> palette = new LinkedHashMap<>(document.palette());
        Map<String, String> colors = new LinkedHashMap<>(document.colors());
        List<ThemeDefinition.Rule> rules = new ArrayList<>(document.rules());
        if (target.scope() == Scope.PALETTE) {
            palette.put(target.role(), expression);
        } else if (target.scope() == Scope.EVERYWHERE) {
            colors.put(target.role(), expression);
            rules = without(rules, target.role(), null, null);
        } else {
            String element = target.scope() == Scope.MENU ? "*" : target.element();
            rules = without(rules, target.role(), target.screen(), target.scope() == Scope.MENU ? null : element);
            rules.add(new ThemeDefinition.Rule(target.screen(), List.of(element), Map.of(target.role(), expression)));
        }
        change(new ThemeDefinition(document.name(), document.description(), palette, colors, rules));
    }

    /**
     * Removes a local override. This is the reset part of Cut; the clipboard keeps the previously resolved color. An
     * inherited value stays inherited instead of being replaced with a guessed default.
     *
     * @param target override to remove
     */
    public void reset(Target target) {
        Map<String, String> palette = new LinkedHashMap<>(document.palette());
        Map<String, String> colors = new LinkedHashMap<>(document.colors());
        List<ThemeDefinition.Rule> rules = new ArrayList<>(document.rules());
        switch (target.scope()) {
            case PALETTE -> palette.remove(target.role());
            case EVERYWHERE -> {
                colors.remove(target.role());
                rules = without(rules, target.role(), null, null);
            }
            case MENU -> rules = without(rules, target.role(), target.screen(), null);
            case ELEMENT -> rules = without(rules, target.role(), target.screen(), target.element());
        }
        change(new ThemeDefinition(document.name(), document.description(), palette, colors, rules));
    }

    /** Changes the internal theme name and description without changing color selectors. */
    public void metadata(String name, String description) {
        change(new ThemeDefinition(name, description, document.palette(), document.colors(), document.rules()));
    }

    /** Restores the previous authoring document. */
    public void undo() {
        endEdit();
        if (undo.isEmpty()) {
            return;
        }
        redo.push(document);
        install(undo.pop());
    }

    /** Reapplies an undone edit. */
    public void redo() {
        endEdit();
        if (redo.isEmpty()) {
            return;
        }
        undo.push(document);
        install(redo.pop());
    }

    /**
     * Validates before modifying history. The first change in a gesture records its starting document; later drag
     * samples replace the current draft without filling the undo stack.
     */
    private void change(ThemeDefinition next) {
        if (document.equals(next)) {
            return;
        }
        ThemeDefinition.Compiled checked = next.compile(defaults);
        if (gestureStart == null)
            undo.push(document);
        else if (!gestureRecorded) {
            undo.push(gestureStart);
            gestureRecorded = true;
        }
        while (undo.size() > 100)
            undo.removeLast();
        redo.clear();
        document = next;
        compiled = checked;
        revision++;
    }

    /**
     * Restores an already validated history snapshot and advances the presentation revision so cached previews cannot
     * retain old colors.
     */
    private void install(ThemeDefinition next) {
        document = next;
        compiled = next.compile(defaults);
        revision++;
    }

    /**
     * Removes only the selected property within the requested scope. Shared rules are split when necessary so sibling
     * elements keep their original overrides.
     */
    private static List<ThemeDefinition.Rule> without(List<ThemeDefinition.Rule> rules, String role, String screen,
            String element) {
        List<ThemeDefinition.Rule> result = new ArrayList<>();
        for (var rule : rules) {
            if ((screen == null || screen.equals(rule.screen()) || rule.screen().startsWith(screen + "/"))
                    && (element == null || rule.elements().contains(element))) {
                if (element != null && rule.elements().size() > 1) {
                    result.add(new ThemeDefinition.Rule(rule.screen(),
                            rule.elements().stream().filter(value -> !value.equals(element)).toList(), rule.colors()));
                }
                Map<String, String> colors = new LinkedHashMap<>(rule.colors());
                colors.remove(role);
                if (!colors.isEmpty())
                    result.add(new ThemeDefinition.Rule(rule.screen(),
                            element == null ? rule.elements() : List.of(element), colors));
            } else {
                result.add(rule);
            }
        }
        return result;
    }
}

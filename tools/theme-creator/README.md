# Avogadro Theme Creator

Open the Windows app, or run the portable JAR with Java 25. The preview tabs use captures from Avogadro's actual menus. Colors are edited beside the preview; **Detach / dock colors** moves the editor into a separate window when useful.

1. The tool opens with **Theme 01**, the light theme. **Start from a theme** creates an editable copy. **Open** edits an existing JSON file. Theme choices show color swatches and can be sorted by rainbow, name, or brightness.
2. Use **Whole theme** for shared palette colors. To change one part, click it in a preview. **Selected part** shows its relevant properties. Choose **Apply color to** to affect one element, that property throughout its menu, or that property in every menu. **Browse all color properties** reveals search, group filters, custom paths, and a JSON view.
3. Pick a color or enter hex. Six digits set RGB; eight set ARGB with opacity first. Short `#RGB` and `#ARGB` values also work. **Saved / Now** swatches compare colors. **Show hover states** previews button hover colors. The wheel zooms, middle drag pans, and **Fit preview** resets the view.
4. Right-click a preview part for color actions. Copy, cut, paste, undo, and redo work across docked and detached views. **Paste color** offers the same three destination scopes. **Cut / reset** copies the color and removes the override. **Link to palette** makes a part follow later palette edits. A continuous color drag or bulk change is one undo step.
5. Choose **Save as / Export**, enter the name shown in Minecraft, and save a `.json` file. **Save** updates the opened file. Cancelling a save keeps the draft; closing with changes offers Save, Discard, and Cancel. Put exported themes in the instance's `config/avogadro/themes` folder, then open **Mod Options → Avogadro → Appearance → Theme**. F3+T reloads edits to an already selected file.

Shortcuts: Ctrl+O opens; Ctrl+S saves; Ctrl+Shift+S exports; Ctrl+Z / Ctrl+Y undo and redo. Ctrl+C / Ctrl+X / Ctrl+V copy, cut, and paste colors; Ctrl+Shift+V chooses a paste scope. Text fields keep their ordinary text-editing shortcuts. Esc in the hex field restores the last valid color.

Previews show the captured layouts and arrangements, rather than running Minecraft. Camera motion, input handling, and particle opacity belong to Minecraft's viewer settings. Color edits are saved in the same theme format used by the mod. Check translucent colors against the live menu when using alpha values.

For development, run `gradlew themeCreatorJar` from the project root. Bundled themes live in `src/main/resources/assets/avogadro/themes`; the build discovers every `.json` file in that folder automatically. The default role list is in `theme-roles.json` alongside it. The creator shares `ThemeDefinition` and `ThemeStore` with the mod.

Element symbols and atomic numbers have independent colors for light and dark tiles. Pick them in the atom or periodic-table preview to edit one element or apply the property across menus. Recipe labels have a translucent overlap-backing color; its alpha controls the maximum opacity of the fading highlight.

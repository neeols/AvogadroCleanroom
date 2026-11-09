/**
 * Standalone authoring workbench for the same theme format used by Minecraft.
 *
 * <p>
 * Follow an edit through {@link io.github.wurtzitane.avogadro.themecreator.ThemeCreator}, the undoable
 * {@link io.github.wurtzitane.avogadro.themecreator.ThemeDraft}, and the shared
 * {@link io.github.wurtzitane.avogadro.theme.ThemeDefinition} resolver. The window owns selection and navigation; the
 * draft owns only authoring data and history. Palette changes and scoped property changes therefore use one validation
 * and inheritance model.
 *
 * <p>
 * PreviewAtlas decodes captured menu images off the Swing event-dispatch thread, then paints and selects their semantic
 * masks on that thread. It provides no independent Minecraft menu implementation. ThemeFiles serializes a validated
 * proposal to a temporary sibling file before replacement; the window records a save only after that operation
 * succeeds.
 *
 * <p>
 * To add a color role, update the shared role catalog and native captures rather than adding a second hard-coded color
 * table here. For user-facing instructions, see the Theme Creator README and the Themes guide in the main Javadocs.
 */
package io.github.wurtzitane.avogadro.themecreator;

/**
 * Builds, registers, and looks up molecule definitions. Safe to use on a client or a dedicated server.
 *
 * <p>
 * New here? Start with <a href="doc-files/start.html">your first molecule</a>, browse the
 * <a href="doc-files/examples.html">rendered examples</a>, or use the <a href="doc-files/reference.html">field and
 * recipe tables</a>.
 *
 * <p>See <a href="doc-files/chirality.html">chirality and R/S</a> and
 * <a href="doc-files/smiles.html">SMILES, external groups, radicals and geometry constraints</a>
 * for the complete authoring examples and in-game previews.</p>
 *
 * <h2>Register a molecule</h2>
 * <p>
 * A builder describes the molecule. Registration makes it available in the game:
 *
 * <pre>{@code
 * var water = AvogadroApi.builder("example:water")
 *     .name("Water")
 *     .smiles("O");
 * AvogadroApi.owner("example").register(water);
 * }</pre>
 * <p>
 * The owner name groups your registrations so you can update or remove your own definitions later. Use
 * {@link io.github.wurtzitane.avogadro.api.AvogadroApi#serverView()} for server decisions. Use the separate client API
 * when you need to respect client resource overrides.
 *
 * <h2>Build a shape from parts</h2>
 * <ul>
 * <li>A <b>fragment</b> is a piece of a molecule: atoms, positions, and bonds.</li>
 * <li>An <b>attachment</b> gives an atom a name, such as {@code head} or {@code tail}, so other parts can connect to
 * it.</li>
 * <li>An <b>assembly</b> is a recipe that places fragments, repeats them, and adds bonds between them.</li>
 * <li>A <b>polymer network</b> uses an assembly with adjustable counts or chances of adding branches and
 * crosslinks.</li>
 * </ul>
 * <p>
 * For example, these calls create a two-atom fragment with named ends:
 *
 * <pre>{@code
 * StructureFragment part = new StructureBuilder()
 *     .atom("C", 0, 0, 0)
 *     .atom("O", 1.4, 0, 0)
 *     .bond(0, 1)
 *     .attachment("head", 0)
 *     .attachment("tail", 1)
 *     .build();
 * }</pre>
 * <p>
 * Atoms and bonds must be supplied by the author. Assembly operations do not optimize positions or repair chemical
 * bonding. Editing returns a new result, leaving the original fragment unchanged.
 */
package io.github.wurtzitane.avogadro.api;

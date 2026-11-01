package io.github.wurtzitane.avogadro.client;

import net.minecraft.client.resources.I18n;

import io.github.wurtzitane.avogadro.api.ChemicalFormula;
import io.github.wurtzitane.avogadro.api.CrystalCell;
import io.github.wurtzitane.avogadro.api.MoleculeWeight;
import io.github.wurtzitane.avogadro.molecule.MoleculeDef;

import java.util.LinkedHashSet;
import java.util.Locale;

/**
 * Renders crystal details and property rows for crystal structures.
 * <p>Cell metrics stay independent of the display repetition.
 */
final class CrystalDetails extends StructureProperties<CrystalDetails> {
    private final MoleculeDef definition;
    private final CrystalCell cell;
    private final CrystalViewport view;

    /**
     * Constructs a new {@link CrystalDetails} view.
     *
     * @param definition molecule definition
     * @param cell       crystal cell configuration
     * @param view       crystal viewport instance
     */
    CrystalDetails(MoleculeDef definition, CrystalCell cell, CrystalViewport view) {
        this.definition = definition;
        this.cell = cell;
        this.view = view;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected long contentRevision() {
        return view.count;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void build(Rows rows) {
        heading(rows, "formula_heading");
        rows.paragraph(ChemicalFormula.display(definition.formula()), StructureStyle.INK);
        rows.y += 2;
        rows.paragraph(MoleculeWeight.calculate(definition).molarMassText(), StructureStyle.MUTED);
        rows.rule();
        heading(rows, "cell_heading");
        rows.paragraph(I18n.format("avogadro.crystal.lengths"), StructureStyle.MUTED);
        rows.y += 4;
        double[] lengths = { cell.a(), cell.b(), cell.c() };
        String[] axes = { "a", "b", "c" };
        for (int row = 0; row < lengths.length; row++) {
            rows.property(axes[row], String.format(Locale.ROOT, "%.4f", lengths[row]));
        }
        rows.y += 4;
        rows.paragraph(I18n.format("avogadro.crystal.angles"), StructureStyle.MUTED);
        rows.y += 4;
        double[] angles = { cell.alpha(), cell.beta(), cell.gamma() };
        String[] names = { "alpha", "beta", "gamma" };
        for (int row = 0; row < angles.length; row++) {
            rows.property(names[row], String.format(Locale.ROOT, "%.2f", angles[row]));
        }
        rows.rule();
        heading(rows, "preview_heading");
        rows.property(I18n.format("avogadro.crystal.cell_total"), Integer.toString(view.count * view.count * view.count));
        rows.property(I18n.format("avogadro.crystal.atom_total"),
                String.format(Locale.ROOT, "%,d", (long) definition.structure().atoms().size() * view.count * view.count * view.count));
        rows.property(I18n.format("avogadro.crystal.source_total"), Integer.toString(definition.structure().atoms().size()));
        var elements = new LinkedHashSet<String>();
        for (var atom : definition.structure().atoms()) {
            elements.add(atom.element().symbol());
        }
        rows.rule();
        heading(rows, "elements_heading");
        rows.paragraph(String.join("   ", elements), StructureStyle.INK);
        var metadata = definition.inspection().metadata();
        for (String key : new String[] { "polymorph", "space_group" }) {
            if (!metadata.containsKey(key)) {
                continue;
            }
            rows.rule();
            heading(rows, key);
            rows.paragraph(metadata.get(key), StructureStyle.INK);
        }
    }

    /** Helper method to add a formatted heading row. */
    private static void heading(Rows rows, String key) {
        rows.heading(I18n.format("avogadro.crystal." + key));
    }
}
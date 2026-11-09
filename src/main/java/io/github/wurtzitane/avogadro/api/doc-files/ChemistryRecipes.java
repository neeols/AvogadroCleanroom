package examples.javadoc;

import java.util.List;
import io.github.wurtzitane.avogadro.api.*;

/** Complete common-side guide definitions. No samples are registered by the library itself. */
public final class ChemistryRecipes {
    private ChemistryRecipes() {}

    /** @return opt-in guide definitions, ready for registration by a host mod or script */
    public static List<MoleculeSpec> catalog() {
        var torsion = StructureConstraints.builder().bondLength(1, 2, 1.54).dihedral(0, 1, 2, 3, 60).build();
        return List.of(
            new MoleculeBuilder("chemistry:lactic_a").name("Lactic acid · S").smiles("C[C@H](O)C(=O)O").build(),
            new MoleculeBuilder("chemistry:lactic_b").name("Lactic acid · R").smiles("C[C@@H](O)C(=O)O").build(),
            new MoleculeBuilder("chemistry:diastereomer_a").name("3-Fluorobutan-2-ol · A").smiles("C[C@H](O)[C@H](F)C").build(),
            new MoleculeBuilder("chemistry:diastereomer_b").name("3-Fluorobutan-2-ol · B").smiles("C[C@H](O)[C@@H](F)C").build(),
            new MoleculeBuilder("chemistry:external_group").name("Generic alcohol · unresolved R1").smiles("[*:1]CCO").build(),
            new MoleculeBuilder("chemistry:radical").name("Methyl radical · one unpaired electron").smiles("[CH3]").radical(0, 1).build(),
            new MoleculeBuilder("chemistry:butane").name("Butane · schematic starting layout").smiles("CCCC").build(),
            new MoleculeBuilder("chemistry:butane_gauche").name("Butane · 60 degree torsion").smiles("CCCC").constraints(torsion).build(),
            new MoleculeBuilder("chemistry:benzene").name("Benzene · aromatic ring").smiles("c1ccccc1").build(),
            new MoleculeBuilder("chemistry:nitrile").name("Acetonitrile · triple bond").smiles("CC#N").build(),
            new MoleculeBuilder("chemistry:carbon_dioxide").name("Carbon dioxide · double bonds").smiles("O=C=O").build(),
            new MoleculeBuilder("chemistry:ions").name("Sodium / chloride · separate components").smiles("[Na+].[Cl-]").build(),
            new MoleculeBuilder("chemistry:ring_stereo").name("Tetrahydrofuran fragment · specified center").smiles("[C@H]1(F)CCCO1").build(),
            new MoleculeBuilder("chemistry:isotope").name("Isotope-sensitive center · CIP only").smiles("CC[C@](CO)([H])[14CH2]C").build()
        );
    }

    /** Registers the guide on the common side during host-mod initialization. */
    public static void register() {
        var batch = new MoleculeBatch();
        catalog().forEach(batch::register);
        AvogadroApi.owner("chemistry_guide").replace(batch).join();
    }
}

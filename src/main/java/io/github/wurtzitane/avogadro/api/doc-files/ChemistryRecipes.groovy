import io.github.wurtzitane.avogadro.api.*

// Post-init registration; each JSON companion supplies exactly these same fields.

mods.avogadro.molecules.register(MoleculeSpec.fromJson("chemistry:benzene", '''
{
  "name": "Benzene · aromatic ring",
  "smiles": "c1ccccc1"
}
'''))

mods.avogadro.molecules.register(MoleculeSpec.fromJson("chemistry:butane", '''
{
  "name": "Butane · schematic starting layout",
  "smiles": "CCCC"
}
'''))

mods.avogadro.molecules.register(MoleculeSpec.fromJson("chemistry:butane_gauche", '''
{
  "name": "Butane · 60 degree torsion",
  "smiles": "CCCC",
  "constraints": [
    {
      "type": "bond_length",
      "atoms": [
        1,
        2
      ],
      "length": 1.54
    },
    {
      "type": "dihedral",
      "atoms": [
        0,
        1,
        2,
        3
      ],
      "degrees": 60.0
    }
  ]
}
'''))

mods.avogadro.molecules.register(MoleculeSpec.fromJson("chemistry:carbon_dioxide", '''
{
  "name": "Carbon dioxide · double bonds",
  "smiles": "O\u003dC\u003dO"
}
'''))

mods.avogadro.molecules.register(MoleculeSpec.fromJson("chemistry:diastereomer_a", '''
{
  "name": "3-Fluorobutan-2-ol · A",
  "smiles": "C[C@H](O)[C@H](F)C"
}
'''))

mods.avogadro.molecules.register(MoleculeSpec.fromJson("chemistry:diastereomer_b", '''
{
  "name": "3-Fluorobutan-2-ol · B",
  "smiles": "C[C@H](O)[C@@H](F)C"
}
'''))

mods.avogadro.molecules.register(MoleculeSpec.fromJson("chemistry:external_group", '''
{
  "name": "Generic alcohol · unresolved R1",
  "smiles": "[*:1]CCO"
}
'''))

mods.avogadro.molecules.register(MoleculeSpec.fromJson("chemistry:ions", '''
{
  "name": "Sodium / chloride · separate components",
  "smiles": "[Na+].[Cl-]"
}
'''))

mods.avogadro.molecules.register(MoleculeSpec.fromJson("chemistry:isotope", '''
{
  "name": "Isotope-sensitive center · CIP only",
  "smiles": "CC[C@](CO)([H])[14CH2]C"
}
'''))

mods.avogadro.molecules.register(MoleculeSpec.fromJson("chemistry:lactic_a", '''
{
  "name": "Lactic acid · S",
  "smiles": "C[C@H](O)C(\u003dO)O"
}
'''))

mods.avogadro.molecules.register(MoleculeSpec.fromJson("chemistry:lactic_b", '''
{
  "name": "Lactic acid · R",
  "smiles": "C[C@@H](O)C(\u003dO)O"
}
'''))

mods.avogadro.molecules.register(MoleculeSpec.fromJson("chemistry:nitrile", '''
{
  "name": "Acetonitrile · triple bond",
  "smiles": "CC#N"
}
'''))

mods.avogadro.molecules.register(MoleculeSpec.fromJson("chemistry:radical", '''
{
  "name": "Methyl radical · one unpaired electron",
  "smiles": "[CH3]",
  "atom_annotations": {
    "0": {
      "radical_electrons": 1
    }
  }
}
'''))

mods.avogadro.molecules.register(MoleculeSpec.fromJson("chemistry:ring_stereo", '''
{
  "name": "Tetrahydrofuran fragment · specified center",
  "smiles": "[C@H]1(F)CCCO1"
}
'''))

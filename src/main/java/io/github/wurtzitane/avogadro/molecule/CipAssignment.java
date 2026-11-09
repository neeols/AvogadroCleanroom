package io.github.wurtzitane.avogadro.molecule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.actelion.research.chem.Molecule;
import com.actelion.research.chem.StereoMolecule;
import com.simolecule.centres.BaseMol;
import com.simolecule.centres.Descriptor;
import com.simolecule.centres.Digraph;
import com.simolecule.centres.Labeller;
import com.simolecule.centres.config.Configuration;
import com.simolecule.centres.config.Tetrahedral;

/**
 * Adapts explicit OpenChemLib graphs to Centres 1.5's CIP sequence rules. Ranking,
 * duplicate nodes for rings/multiple bonds, isotopes and recursive stereochemical
 * comparisons belong to Centres, not a local atomic-number sorting approximation.
 */
final class CipAssignment extends BaseMol<Integer, Integer> {
    /** Explicit graph, including hydrogen and isotope masses. */
    private final StereoMolecule molecule;
    /** Stable identities are important even for atom indices outside Integer's small-value cache. */
    private final Integer[] atoms, bonds;
    /** Load-local properties written by the CIP engine; never attached to render data. */
    private final Map<Integer, Map<String, Object>> atomProperties = new HashMap<>(), bondProperties = new HashMap<>();
    /** Bounds recursive ranking work for pathological ring/ligand graphs. */
    private int visits;

    /**
     * Creates the common-side adapter after geometry generation has made every hydrogen explicit.
     * @param molecule fully populated chemistry graph
     */
    private CipAssignment(StereoMolecule molecule) {
        this.molecule = molecule;
        atoms = new Integer[molecule.getAllAtoms()];
        bonds = new Integer[molecule.getAllBonds()];
        for (int i = 0; i < atoms.length; i++) atoms[i] = i;
        for (int i = 0; i < bonds.length; i++) bonds[i] = i;
    }

    /**
     * Assigns only the tetrahedral centers explicitly present in the source SMILES.
     * @param molecule geometry whose handedness has already been independently checked
     * @param specified atom-index parities, excluding every unspecified center
     * @return absolute R/S labels; no guessed label is returned for unresolved priorities
     * @throws IllegalArgumentException if assignment fails or yields an unsupported descriptor
     */
    static Map<Integer, StereoConfiguration> assign(StereoMolecule molecule, Map<Integer, Integer> specified) {
        var graph = new CipAssignment(molecule);
        List<Configuration<Integer, Integer>> configurations = new ArrayList<>();
        for (int atom : specified.keySet()) {
            Integer[] neighbors = new Integer[4];
            for (int i = 0; i < 4; i++) neighbors[i] = graph.atoms[molecule.getConnAtom(atom, i)];
            Arrays.sort(neighbors);
            // OCL parity 1: ascending first three clockwise with the highest index away.
            // With sorted carriers, Centres' viewpoint change and carrier permutation cancel.
            int parity = specified.get(atom) == Molecule.cAtomParity1 ? Tetrahedral.RIGHT : Tetrahedral.LEFT;
            configurations.add(new Tetrahedral<>(graph.atoms[atom], neighbors, parity));
        }
        new Labeller<Integer, Integer>().label(graph, configurations);
        MoleculeTasks.checkCancelled();
        Map<Integer, StereoConfiguration> result = new HashMap<>();
        for (int atom : specified.keySet()) {
            Descriptor descriptor = graph.getAtomProp(graph.atoms[atom], CIP_LABEL_KEY);
            if (descriptor != Descriptor.R && descriptor != Descriptor.S)
                throw new IllegalArgumentException("CIP assignment at atom " + atom + " is unresolved or unsupported ("
                        + descriptor + "); pseudoasymmetric and other non-R/S cases are not accepted");
            result.put(atom, descriptor == Descriptor.R ? StereoConfiguration.R : StereoConfiguration.S);
        }
        return Map.copyOf(result);
    }

    /** Throws before an adversarial recursive ligand comparison can run without a bound. */
    private void visit() {
        if ((++visits & 1023) == 0) MoleculeTasks.checkCancelled();
        if (visits > 2_000_000) throw new IllegalArgumentException("CIP graph expansion exceeded its work limit");
    }

    /** {@inheritDoc} */
    @Override public Object getBaseImpl() { return molecule; }
    /** {@inheritDoc} */
    @Override public int getNumAtoms() { return atoms.length; }
    /** {@inheritDoc} */
    @Override public int getNumBonds() { return bonds.length; }
    /** {@inheritDoc} */
    @Override public Integer getAtom(int index) { return atoms[index]; }
    /** {@inheritDoc} */
    @Override public int getAtomIdx(Integer atom) { return atom; }
    /** {@inheritDoc} */
    @Override public Integer getBond(int index) { return bonds[index]; }
    /** {@inheritDoc} */
    @Override public int getBondIdx(Integer bond) { return bond; }
    /** {@inheritDoc} */
    @Override public Iterable<Integer> getBonds(Integer atom) {
        visit();
        List<Integer> result = new ArrayList<>(molecule.getAllConnAtoms(atom));
        for (int i = 0; i < molecule.getAllConnAtoms(atom); i++) result.add(bonds[molecule.getConnBond(atom, i)]);
        return result;
    }
    /** {@inheritDoc} */
    @Override public Integer getOther(Integer bond, Integer atom) {
        return atoms[molecule.getBondAtom(0, bond) == atom ? molecule.getBondAtom(1, bond) : molecule.getBondAtom(0, bond)];
    }
    /** {@inheritDoc} */
    @Override public Integer getBeg(Integer bond) { return atoms[molecule.getBondAtom(0, bond)]; }
    /** {@inheritDoc} */
    @Override public Integer getEnd(Integer bond) { return atoms[molecule.getBondAtom(1, bond)]; }
    /** {@inheritDoc} */
    @Override public boolean isInRing(Integer bond) { return molecule.isRingBond(bond); }
    /** {@inheritDoc} */
    @Override public int getAtomicNum(Integer atom) { visit(); return molecule.getAtomicNo(atom); }
    /** {@inheritDoc} */
    @Override public int getNumHydrogens(Integer atom) { return 0; }
    /** {@inheritDoc} */
    @Override public int getMassNum(Integer atom) { return molecule.getAtomMass(atom); }
    /** {@inheritDoc} */
    @Override public int getCharge(Integer atom) { return molecule.getAtomCharge(atom); }
    /** {@inheritDoc} */
    @Override public int getBondOrder(Integer bond) { return molecule.getBondOrder(bond); }
    /** {@inheritDoc} */
    @Override public void setAtomProp(Integer atom, String key, Object value) {
        atomProperties.computeIfAbsent(atom, ignored -> new HashMap<>()).put(key, value);
    }
    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public <V> V getAtomProp(Integer atom, String key) {
        return (V) atomProperties.getOrDefault(atom, Map.of()).get(key);
    }
    /** {@inheritDoc} */
    @Override public void setBondProp(Integer bond, String key, Object value) {
        bondProperties.computeIfAbsent(bond, ignored -> new HashMap<>()).put(key, value);
    }
    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public <V> V getBondProp(Integer bond, String key) {
        return (V) bondProperties.getOrDefault(bond, Map.of()).get(key);
    }
    /** {@inheritDoc} */
    @Override public String dumpDigraph(Digraph<Integer, Integer> digraph) { return "Avogadro tetrahedral CIP graph"; }
}

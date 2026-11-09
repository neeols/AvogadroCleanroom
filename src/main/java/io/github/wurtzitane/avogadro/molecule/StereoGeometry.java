package io.github.wurtzitane.avogadro.molecule;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import com.actelion.research.chem.Canonizer;
import com.actelion.research.chem.Molecule;
import com.actelion.research.chem.StereoMolecule;
import org.openmolecules.chem.conf.gen.ConformerGenerator;

/**
 * Load-time bridge to OpenChemLib's ordered SMILES parser and conformer generator.
 * No chemistry toolkit objects escape into rendering. A canonical representative of an
 * enantiomeric pair is generated once deterministically; its partner is reflected in X.
 * Every specified center is checked again from the final float coordinates, with all
 * inherited stereo flags removed, before the structure can enter the geometry cache.
 */
final class StereoGeometry {
    /** Bracket atoms containing a chirality annotation; SMARTS and extended stereo classes are excluded. */
    private static final Pattern MARKED = Pattern.compile("\\[[^\\[\\]]*@[^\\[\\]]*\\]");
    /** Fixed nonzero seed makes equivalent canonical input independent of wall-clock timing. */
    private static final long SEED = 0x41564f474144524fL;
    /** Conformer search is for small molecules, not unbounded polymers or crystal expansions. */
    private static final int MAX_ATOMS = 512;

    private StereoGeometry() {}

    /**
     * Parses, canonicalizes, generates and validates a tetrahedral structure.
     * @param id definition identifier for diagnostics
     * @param smiles SMILES containing at least one explicit tetrahedral annotation
     * @return full geometry with explicit hydrogen and immutable absolute assignments
     * @throws IllegalArgumentException for unsupported, invalid or unresolved stereochemistry
     */
    static MoleculeStructure parse(String id, String smiles) {
        int marked = validateSyntax(smiles);
        StereoMolecule molecule = new StereoMolecule();
        try {
            var parser = new com.actelion.research.chem.SmilesParser(
                    com.actelion.research.chem.SmilesParser.MODE_NO_CACTUS_SYNTAX
                            | com.actelion.research.chem.SmilesParser.MODE_NO_STACKTRACES);
            parser.setRandomSeed(SEED);
            parser.parse(molecule, smiles);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid stereochemical SMILES for " + id + ": " + invalid.getMessage(), invalid);
        }
        molecule.ensureHelperArrays(Molecule.cHelperParities);
        validateCenters(molecule, marked);
        // Do not let the backend silently discard specified isotopic/explicit hydrogen neighbors.
        ConformerGenerator.addHydrogenAtoms(molecule);
        if (molecule.getAllAtoms() > MAX_ATOMS)
            throw new IllegalArgumentException("Stereochemical SMILES exceeds " + MAX_ATOMS + " atoms including hydrogen; use explicit geometry");

        StereoMolecule mirror = new StereoMolecule(molecule);
        for (int atom = 0; atom < mirror.getAllAtoms(); atom++)
            mirror.setAtomX(atom, -mirror.getAtomX(atom));
        mirror.invalidateHelperArrays(Molecule.cHelperBitsStereo);
        mirror.ensureHelperArrays(Molecule.cHelperParities);
        Canonizer originalCode = new Canonizer(molecule), mirrorCode = new Canonizer(mirror);
        boolean reflect = mirrorCode.getIDCode().compareTo(originalCode.getIDCode()) < 0;
        molecule = (reflect ? mirrorCode : originalCode).getCanMolecule(true);
        molecule.ensureHelperArrays(Molecule.cHelperParities);
        Map<Integer, Integer> expected = parities(molecule);
        if (expected.size() != marked)
            throw new IllegalArgumentException("Canonicalization could not retain every specified tetrahedral center");

        var generator = new ConformerGenerator(SEED, false);
        generator.setThreadMaster(() -> Thread.currentThread().isInterrupted());
        generator.setTimeOut(5000);
        MoleculeTasks.checkCancelled();
        if (generator.getOneConformerAsMolecule(molecule) == null)
            throw new IllegalArgumentException("Could not generate stereochemistry-preserving coordinates within the bounded conformer search; use explicit geometry");
        MoleculeTasks.checkCancelled();
        // Round to the actual renderer's representation before validating: near-planar centers must not pass in doubles only.
        for (int atom = 0; atom < molecule.getAllAtoms(); atom++) {
            molecule.setAtomX(atom, (float) (molecule.getAtomX(atom) * (reflect ? -1 : 1)));
            molecule.setAtomY(atom, (float) molecule.getAtomY(atom));
            molecule.setAtomZ(atom, (float) molecule.getAtomZ(atom));
        }
        if (reflect)
            expected.replaceAll((atom, parity) -> 3 - parity);
        verifyCoordinates(molecule, expected);
        Map<Integer, StereoConfiguration> assignments = CipAssignment.assign(molecule, expected);
        var builder = MoleculeStructure.builder(id, "", molecule.getAllAtoms(), molecule.getAllBonds());
        for (int atom = 0; atom < molecule.getAllAtoms(); atom++)
            builder.addAtom(AtomElement.bySymbol(molecule.getAtomLabel(atom)), (float) molecule.getAtomX(atom),
                    (float) molecule.getAtomY(atom), (float) molecule.getAtomZ(atom));
        for (int bond = 0; bond < molecule.getAllBonds(); bond++) {
            BondType type = switch (molecule.getBondOrder(bond)) {
                case 1 -> BondType.SINGLE;
                case 2 -> BondType.DOUBLE;
                case 3 -> BondType.TRIPLE;
                default -> throw new IllegalArgumentException("Unsupported bond order in stereochemical SMILES");
            };
            builder.bond(molecule.getBondAtom(0, bond), molecule.getBondAtom(1, bond), type);
        }
        return builder.build().withStereochemistry(assignments);
    }

    /**
     * Counts supported annotations and rejects syntax that the old parser silently discarded.
     * @param smiles source notation
     * @return number of explicitly annotated bracket atoms
     */
    private static int validateSyntax(String smiles) {
        if (smiles.length() > 8192)
            throw new IllegalArgumentException("Stereochemical SMILES is limited to 8192 characters");
        if (smiles.indexOf('/') >= 0 || smiles.indexOf('\\') >= 0 || smiles.indexOf('>') >= 0
                || smiles.indexOf('|') >= 0 || smiles.indexOf('*') >= 0)
            throw new IllegalArgumentException("Only absolute tetrahedral @/@@ stereochemistry is supported; directional bonds, reactions, wildcards and CXSMILES require explicit geometry");
        int count = 0, tokens = 0;
        var matcher = MARKED.matcher(smiles);
        while (matcher.find()) {
            String atom = matcher.group();
            int at = atom.indexOf('@'), end = at + 1;
            if (atom.charAt(end) == '@') end++;
            char next = atom.charAt(end);
            if (next != 'H' && next != ']' && next != '+' && next != '-' && next != ':')
                throw new IllegalArgumentException("Unsupported chirality annotation: " + atom + "; use tetrahedral @ or @@");
            tokens += end - at;
            count++;
        }
        if (count == 0 || smiles.chars().filter(c -> c == '@').count() != tokens)
            throw new IllegalArgumentException("Chirality must be a single @ or @@ annotation inside each bracket atom");
        return count;
    }

    /**
     * Ensures every annotation actually identifies a supported four-ligand tetrahedron.
     * @param molecule parsed graph with ordered parities
     * @param marked number of source annotations
     */
    private static void validateCenters(StereoMolecule molecule, int marked) {
        Map<Integer, Integer> centers = parities(molecule);
        if (centers.size() != marked)
            throw new IllegalArgumentException("A chirality annotation is invalid, non-stereogenic or unsupported; no specified center may be ignored");
        for (int atom : centers.keySet()) {
            if (molecule.isAtomParityPseudo(atom) || molecule.getAtomESRType(atom) != Molecule.cESRTypeAbs
                    || molecule.getAllConnAtoms(atom) + molecule.getImplicitHydrogens(atom) != 4
                    || molecule.getAtomPi(atom) != 0)
                throw new IllegalArgumentException("Atom " + atom + " is not a supported absolute tetrahedral center; lone-pair, axial and pseudoasymmetric cases require explicit geometry");
        }
    }

    /**
     * Records only specified atom-index parities; unknown centers are deliberately absent.
     * @param molecule parsed or canonical graph
     * @return specified atom indices and backend parity values
     */
    private static Map<Integer, Integer> parities(StereoMolecule molecule) {
        Map<Integer, Integer> result = new HashMap<>();
        for (int atom = 0; atom < molecule.getAtoms(); atom++) {
            int parity = molecule.getAtomParity(atom);
            if (parity == Molecule.cAtomParity1 || parity == Molecule.cAtomParity2)
                result.put(atom, parity);
        }
        return result;
    }

    /**
     * Reconstructs parities solely from stored coordinates, rejecting inversions and flat centers.
     * @param molecule generated molecule, mutated to remove stale depiction stereo flags
     * @param expected explicitly requested atom-index parities
     */
    private static void verifyCoordinates(StereoMolecule molecule, Map<Integer, Integer> expected) {
        for (int bond = 0; bond < molecule.getAllBonds(); bond++)
            if (molecule.isStereoBond(bond)) molecule.setBondType(bond, Molecule.cBondTypeSingle);
        for (int atom = 0; atom < molecule.getAllAtoms(); atom++) {
            if (!Double.isFinite(molecule.getAtomX(atom)) || !Double.isFinite(molecule.getAtomY(atom))
                    || !Double.isFinite(molecule.getAtomZ(atom)))
                throw new IllegalArgumentException("Conformer contains nonfinite coordinates");
            molecule.setAtomParity(atom, Molecule.cAtomParityNone, false);
        }
        molecule.invalidateHelperArrays(Molecule.cHelperBitsStereo);
        molecule.ensureHelperArrays(Molecule.cHelperParities);
        for (var entry : expected.entrySet()) {
            int atom = entry.getKey();
            if (molecule.getAtomParity(atom) != entry.getValue() || Math.abs(volume(molecule, atom)) < 0.01)
                throw new IllegalArgumentException("Generated geometry does not preserve tetrahedral configuration at atom " + atom);
        }
    }

    /**
     * Computes six times the signed tetrahedron volume of the four explicit ligands.
     * @param molecule complete coordinates and neighbors
     * @param atom center index
     * @return signed volume, or zero when a ligand is missing
     */
    private static double volume(StereoMolecule molecule, int atom) {
        if (molecule.getAllConnAtoms(atom) != 4) return 0;
        var origin = molecule.getAtomCoordinates(molecule.getConnAtom(atom, 3));
        var a = molecule.getAtomCoordinates(molecule.getConnAtom(atom, 0)).subC(origin);
        var b = molecule.getAtomCoordinates(molecule.getConnAtom(atom, 1)).subC(origin);
        var c = molecule.getAtomCoordinates(molecule.getConnAtom(atom, 2)).subC(origin);
        return a.dot(b.cross(c));
    }
}

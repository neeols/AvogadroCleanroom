package io.github.wurtzitane.avogadro.client;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

import io.github.wurtzitane.avogadro.api.AtomicConfiguration;
import io.github.wurtzitane.avogadro.molecule.AtomElement;
import io.github.wurtzitane.avogadro.molecule.MoleculeDef;
import io.github.wurtzitane.avogadro.molecule.MoleculeStructure;

/**
 * Caches immutable inspection model data shared when molecule definitions are reopened.
 * <p>
 * Immutable inspection data shared when the same definition is reopened. No GPU handles.
 */
final class InspectionModelData {

    /** Cache array storing inspection entries. */
    private static final Entry[] CACHE = new Entry[16];

    /** Maximum allowed atoms and bonds limits for cache retention. */
    private static final int MAX_ATOMS = 65_536, MAX_BONDS = 131_072;

    /** LRU clock counter and retained cache size trackers. */
    private static long clock;
    private static int retainedAtoms, retainedBonds;

    /**
     * Represents a cached inspection entry with a weak reference to its molecule definition.
     */
    private static final class Entry {
        final WeakReference<MoleculeDef> definition;
        final InspectionModelData data;
        long used;

        /** Creates a inspection cache entry. */
        Entry(MoleculeDef definition, InspectionModelData data) {
            this.definition = new WeakReference<>(definition);
            this.data = data;
            used = ++clock;
        }
    }

    private final AtomicConfiguration[] configurations;
    private final MoleculeStructure bonds;
    private final MoleculeStructure cloudMask;

    /** Constructs inspection model data for the specified molecule definition. */
    private InspectionModelData(MoleculeDef definition) {
        var atoms = definition.structure().atoms();
        configurations = new AtomicConfiguration[atoms.size()];
        var links = MoleculeStructure.builder(definition.id() + "/inspection-bonds", "");
        var mask = MoleculeStructure.builder(definition.id() + "/inspection-cloud-mask", "");
        Map<AtomElement, AtomElement> points = new HashMap<>();
        for (int i = 0; i < atoms.size(); i++) {
            GeometryPreparation.checkCancelled();
            var atom = atoms.get(i);
            configurations[i] = definition.inspection().atom(definition.structure(), i);
            AtomElement point = points.computeIfAbsent(atom.element(),
                    element -> new AtomElement(element.symbol(), element.color(), 0));
            links.addAtom(point, atom.x(), atom.y(), atom.z());
            mask.addAtom(atom.element(), atom.x(), atom.y(), atom.z());
        }
        for (var bond : definition.structure().bonds()) {
            GeometryPreparation.checkCancelled();
            links.bond(bond.from(), bond.to(), bond.type());
        }
        bonds = links.build();
        cloudMask = mask.build();
        int missing = 0;
        for (int index = 0; index < configurations.length; index++) {
            if (configurations[index] == null && canInspect(definition.structure(), index)) {
                missing++;
            }
        }
        if (missing > 0) {
            io.github.wurtzitane.avogadro.Avogadro.LOGGER.warn(
                    "Atom examiner: {} has {} atoms without registered atomic configurations. Electron paths and electrons are unavailable for those atoms; supply element or per-atom nuclear/electron data.",
                    definition.id(), missing);
        }
    }

    /**
     * Returns or builds inspection model data for the given molecule definition.
     *
     * @param definition molecule definition
     * @return inspection model data instance
     */
    static InspectionModelData get(MoleculeDef definition) {
        GeometryPreparation.checkCancelled();
        synchronized (CACHE) {
            InspectionModelData cached = find(definition);
            if (cached != null) {
                return cached;
            }
        }
        InspectionModelData built = new InspectionModelData(definition);
        GeometryPreparation.checkCancelled();
        int atoms = built.bonds.atoms().size(), bonds = built.bonds.bonds().size();
        if (atoms > MAX_ATOMS || bonds > MAX_BONDS) {
            return built;
        }
        synchronized (CACHE) {
            GeometryPreparation.checkCancelled();
            InspectionModelData cached = find(definition);
            if (cached != null) {
                return cached;
            }
            while (retainedAtoms + atoms > MAX_ATOMS || retainedBonds + bonds > MAX_BONDS) {
                evict(oldest());
            }
            int slot = oldest();
            for (int i = 0; i < CACHE.length; i++) {
                if (CACHE[i] == null) {
                    slot = i;
                    break;
                }
            }
            evict(slot);
            CACHE[slot] = new Entry(definition, built);
            retainedAtoms += atoms;
            retainedBonds += bonds;
        }
        return built;
    }

    /** Returns the atomic configuration at the specified atom index. */
    AtomicConfiguration configuration(int index) {
        return configurations[index];
    }

    /** Real atoms, including authored radicals, can be inspected; an R group has no nucleus. */
    static boolean canInspect(MoleculeStructure structure, int index) {
        return index >= 0 && index < structure.atoms().size()
                && !structure.atoms().get(index).element().symbol().equals("*");
    }

    /** Returns the inspection bonds molecule structure. */
    MoleculeStructure bonds() {
        return bonds;
    }

    /** Returns the inspection cloud mask molecule structure. */
    MoleculeStructure cloudMask() {
        return cloudMask;
    }

    /** Finds cached inspection model data for the given molecule definition. */
    private static InspectionModelData find(MoleculeDef definition) {
        for (int i = 0; i < CACHE.length; i++) {
            Entry entry = CACHE[i];
            if (entry == null) {
                continue;
            }
            MoleculeDef retained = entry.definition.get();
            if (retained == null) {
                evict(i);
            } else if (retained == definition) {
                entry.used = ++clock;
                return entry.data;
            }
        }
        return null;
    }

    /** Finds the oldest cache entry slot. */
    private static int oldest() {
        int oldest = -1;
        for (int i = 0; i < CACHE.length; i++) {
            if (CACHE[i] != null && (oldest < 0 || CACHE[i].used < CACHE[oldest].used)) {
                oldest = i;
            }
        }
        return oldest;
    }

    /** Evicts a cache entry slot at the specified index. */
    private static void evict(int slot) {
        if (slot < 0 || CACHE[slot] == null) {
            return;
        }
        retainedAtoms -= CACHE[slot].data.bonds.atoms().size();
        retainedBonds -= CACHE[slot].data.bonds.bonds().size();
        CACHE[slot] = null;
    }
}

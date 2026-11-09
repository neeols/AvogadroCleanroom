package io.github.wurtzitane.avogadro.api;

import java.util.List;
import java.util.Objects;

/**
 * The edited fragment and a map showing where each original atom ended up.
 *
 * <p>
 * Look up an original atom's index in {@link #oldToNew()} to get its final index. A value of -1 means that atom was
 * removed.
 *
 * @param fragment the edited {@link StructureFragment} result
 * @param oldToNew immutable list mapping original atom indices to their new indices, or -1 if removed
 */
public record StructureEditResult(StructureFragment fragment, List<Integer> oldToNew) {

    /**
     * Compact constructor for {@link StructureEditResult} that validates null safety, creates an immutable defensive
     * copy of the index mapping list, and ensures all mapped indices fall within valid bounds of the resulting
     * fragment.
     *
     * @throws NullPointerException if the fragment is null
     * @throws IllegalArgumentException if any mapped index is out of bounds ({@code < -1} or
     *     {@code >= fragment.atoms().size()})
     */
    public StructureEditResult {
        Objects.requireNonNull(fragment, "fragment");
        oldToNew = List.copyOf(oldToNew);
        for (int index : oldToNew) {
            if (index < -1 || index >= fragment.atoms().size()) {
                throw new IllegalArgumentException("Invalid edited atom mapping.");
            }
        }
    }

    /**
     * Remaps an original atom index to its new index in the edited structure fragment.
     *
     * @param originalIndex the original zero-based atom index
     * @return the new zero-based atom index, or {@code -1} if the atom was removed
     */
    public int remap(int originalIndex) {
        return oldToNew.get(originalIndex);
    }
}

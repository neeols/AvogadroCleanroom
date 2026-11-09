package io.github.wurtzitane.avogadro.client;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import io.github.wurtzitane.avogadro.api.CrystalCell;
import io.github.wurtzitane.avogadro.api.CrystalGeometry;
import io.github.wurtzitane.avogadro.molecule.MoleculeDef;
import io.github.wurtzitane.avogadro.molecule.MoleculeStructure;

/**
 * Caches CPU-only lattice geometry results and outline previews.
 * <p>
 * CPU-only lattice results. Keeping their identity also preserves resident GPU geometry.
 */
final class CrystalPreviewCache {

    /** Maximum number of cached preview entries. */
    private static final int MAX_ENTRIES = 16;

    /** Maximum cache memory size in bytes (64 MiB). */
    private static final long MAX_BYTES = 64L * 1024 * 1024;

    /** Ready preview cache map using LRU ordering. */
    private static final Map<Key, Prepared> READY = new LinkedHashMap<>(16, .75f, true);

    /** Map tracking pending asynchronous preparation tasks. */
    private static final Map<Key, Pending> PENDING = new HashMap<>();

    /** Tracked retained cache size in bytes and generation counter. */
    private static long retainedBytes, generation;

    /**
     * Record representing a cache key for crystal preview lookup.
     *
     * @param source source molecule structure
     * @param cell crystal cell configuration
     * @param count repetition count per axis
     * @param bonds whether local bonds are enabled
     */
    private record Key(MoleculeStructure source, CrystalCell cell, int count, boolean bonds) {
    }

    /**
     * Record representing prepared crystal preview data.
     *
     * @param geometry repeated molecule structure geometry
     * @param outline crystal outline renderer
     * @param fit fit scale factor
     */
    record Prepared(MoleculeStructure geometry, CrystalOutline outline, float fit) {
    }

    /**
     * Represents a pending asynchronous preparation task.
     */
    private static final class Pending {
        final CompletableFuture<Prepared> future = new CompletableFuture<>();
        volatile boolean invalidated;

        /** Invalidates and cancels the pending future. */
        void invalidate() {
            invalidated = true;
            future.cancel(false);
        }
    }

    private CrystalPreviewCache() {}

    /**
     * Finds a prepared crystal preview from the cache synchronously.
     *
     * @param source source molecule structure
     * @param cell crystal cell configuration
     * @param count repetition count per axis
     * @param bonds whether local bonds are enabled
     * @return prepared crystal preview, or {@code null} if not cached
     */
    static synchronized Prepared find(MoleculeStructure source, CrystalCell cell, int count, boolean bonds) {
        return READY.get(new Key(source, cell, count, bonds));
    }

    /**
     * Prepares crystal geometry and outlines.
     * <p>
     * Called by a preparation worker, never by the drawing thread on a miss.
     *
     * @param source source molecule structure
     * @param cell crystal cell configuration
     * @param count repetition count per axis
     * @param bonds whether local bonds are enabled
     * @return prepared crystal preview instance
     */
    static Prepared prepare(MoleculeStructure source, CrystalCell cell, int count, boolean bonds) {
        long requestedGeneration;
        synchronized (CrystalPreviewCache.class) {
            requestedGeneration = generation;
        }
        return prepare(new Key(source, cell, count, bonds), requestedGeneration);
    }

    /** Prepares a crystal preview for the given key and generation. */
    private static Prepared prepare(Key key, long requestedGeneration) {
        GeometryPreparation.checkCancelled();
        Pending pending;
        boolean owner;
        synchronized (CrystalPreviewCache.class) {
            if (generation != requestedGeneration) {
                throw new CancellationException();
            }
            Prepared cached = READY.get(key);
            if (cached != null) {
                return cached;
            }
            pending = PENDING.get(key);
            owner = pending == null;
            if (owner) {
                pending = new Pending();
                PENDING.put(key, pending);
            }
        }
        if (!owner) {
            try {
                return await(pending.future);
            } catch (CancellationException cancelled) {
                // A closed viewer cancels its worker, not another viewer's replacement request.
                if (pending.invalidated || Thread.currentThread().isInterrupted()) {
                    throw cancelled;
                }
                return prepare(key, requestedGeneration);
            }
        }
        try {
            int count = key.count;
            MoleculeStructure geometry = CrystalGeometry.repeat(key.source, key.cell, count, key.bonds);
            CrystalOutline outline = new CrystalOutline(key.cell, count);
            float bound = geometry.radius();
            var center = geometry.center();
            for (int x = 0; x <= count; x += count) {
                for (int y = 0; y <= count; y += count) {
                    for (int z = 0; z <= count; z += count) {
                        bound = Math.max(bound, key.cell.position(x, y, z).distance(center));
                    }
                }
            }
            Prepared result = new Prepared(geometry, outline, bound > 0 ? geometry.radius() / bound : 1);
            GeometryPreparation.checkCancelled();
            synchronized (CrystalPreviewCache.class) {
                // A reload/teardown must not let a late worker refill the cache.
                if (!PENDING.remove(key, pending)) {
                    throw new CancellationException();
                }
                retain(key, result);
                pending.future.complete(result);
            }
            return result;
        } catch (RuntimeException | Error failure) {
            synchronized (CrystalPreviewCache.class) {
                PENDING.remove(key, pending);
                pending.future.completeExceptionally(failure);
            }
            throw failure;
        }
    }

    /** Awaits completion of a pending asynchronous preparation future. */
    private static Prepared await(CompletableFuture<Prepared> pending) {
        try {
            return pending.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException();
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            if (failure.getCause() instanceof Error cause) {
                throw cause;
            }
            throw new IllegalStateException("Cannot prepare crystal preview", failure.getCause());
        }
    }

    /** Retains a prepared preview in the cache, enforcing memory limits. */
    private static void retain(Key key, Prepared value) {
        long bytes = weight(key, value);
        if (bytes > MAX_BYTES) {
            return;
        }
        var oldest = READY.entrySet().iterator();
        while ((READY.size() >= MAX_ENTRIES || retainedBytes + bytes > MAX_BYTES) && oldest.hasNext()) {
            var entry = oldest.next();
            retainedBytes -= weight(entry.getKey(), entry.getValue());
            oldest.remove();
        }
        READY.put(key, value);
        retainedBytes += bytes;
    }

    /** Calculates the estimated memory weight of a cached entry. */
    private static long weight(Key key, Prepared value) {
        // Conservative heap allowance includes the retained source, records, lists and cell edges.
        return 512L + 128L * (key.source.atoms().size() + (long) value.geometry.atoms().size())
                + 64L * (key.source.bonds().size() + (long) value.geometry.bonds().size())
                + 72L * (key.count + 1) * (key.count + 1) * key.count;
    }

    /**
     * Retains cache entries for specified definitions during resource reloads.
     * <p>
     * Unchanged immutable definitions survive a font/palette reload; replaced geometry does not.
     *
     * @param definitions collection of active molecule definitions
     */
    static synchronized void retainDefinitions(Collection<MoleculeDef> definitions) {
        Set<MoleculeStructure> sources = Collections.newSetFromMap(new IdentityHashMap<>());
        for (MoleculeDef definition : definitions) {
            sources.add(definition.structure());
        }
        var ready = READY.entrySet().iterator();
        while (ready.hasNext()) {
            var entry = ready.next();
            if (sources.contains(entry.getKey().source)) {
                continue;
            }
            retainedBytes -= weight(entry.getKey(), entry.getValue());
            ready.remove();
        }
        PENDING.entrySet().removeIf(entry -> {
            if (sources.contains(entry.getKey().source)) {
                return false;
            }
            entry.getValue().invalidate();
            return true;
        });
    }

    /** Clears the preview cache and increments the generation counter. */
    static synchronized void clear() {
        generation++;
        PENDING.values().forEach(Pending::invalidate);
        PENDING.clear();
        READY.clear();
        retainedBytes = 0;
    }
}

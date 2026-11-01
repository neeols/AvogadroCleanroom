package io.github.wurtzitane.avogadro.client;

import io.github.wurtzitane.avogadro.Avogadro;
import io.github.wurtzitane.avogadro.molecule.MoleculeStructure;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/** Full-resolution, read-only model-space payloads prepared without an OpenGL context. */
final class PreparedMoleculeGeometry {

    /** Maximum number of concurrent prepared molecule jobs cached. */
    private static final int MAX_JOBS = 256;

    /** Maximum byte storage limit allocated for prepared geometry caches (128 MiB). */
    private static final long MAX_BYTES = 128L * 1024L * 1024L;

    /** Linked map maintaining active geometry jobs. */
    private static final LinkedHashMap<Key, Job> JOBS = new LinkedHashMap<>(32, .75f, true);

    /** Total currently reserved byte size across all cached jobs. */
    private static long reserved;

    /**
     * Record representing a cache lookup key for prepared molecule geometry.
     *
     * @param molecule  molecule structure
     * @param cubic     whether geometry is cubic
     * @param palette   element palette revision identifier
     * @param instanced whether geometry is instanced
     * @param style     molecule render style
     */
    private record Key(MoleculeStructure molecule, boolean cubic, long palette, boolean instanced, MoleculeStyle style) {}

    /**
     * Record holding raw byte buffer data for prepared atoms and bonds.
     *
     * @param atoms      atom vertex/instance byte buffer
     * @param bonds      bond vertex/instance byte buffer
     * @param atomCount  total count of atoms
     * @param bondCount  total count of bonds
     * @param instanced  whether data is instanced
     */
    record Data(ByteBuffer atoms, ByteBuffer bonds, int atomCount, int bondCount, boolean instanced) {

        /** Compact constructor wrapping buffers in read-only order. */
        Data {
            atoms = readOnly(atoms);
            bonds = readOnly(bonds);
        }

        /**
         * Retrieves the read-only atoms byte buffer.
         *
         * @return atoms buffer
         */
        @Override public ByteBuffer atoms() { return readOnly(atoms); }

        /**
         * Retrieves the read-only bonds byte buffer.
         *
         * @return bonds buffer
         */
        @Override public ByteBuffer bonds() { return readOnly(bonds); }

        /**
         * Calculates total byte size consumed by atom and bond buffers.
         *
         * @return byte size
         */
        long bytes() { return atoms.remaining() + (long) bonds.remaining(); }
    }

    /**
     * Represents a background preparation job for molecule geometry.
     */
    static final class Job {
        final Key key;
        final long bytes;
        final Future<Data> future;
        private boolean reported;

        /**
         * Constructs a new geometry preparation job.
         *
         * @param key    cache key
         * @param bytes  estimated byte size
         * @param future asynchronous preparation future
         */
        private Job(Key key, long bytes, Future<Data> future) {
            this.key = key;
            this.bytes = bytes;
            this.future = future;
        }

        /**
         * Retrieves the prepared data if execution has completed without blocking.
         *
         * @return prepared {@link Data}, or {@code null} if incomplete or failed
         */
        Data ready() {
            if (!future.isDone()) return null;
            try {
                return future.get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            } catch (ExecutionException failure) {
                if (!reported) {
                    reported = true;
                    Avogadro.LOGGER.error("Cannot prepare molecule geometry {}", key.molecule.id(), failure.getCause());
                }
                return null;
            } catch (CancellationException cancelled) {
                return null;
            }
        }

        /**
         * Awaits and retrieves the prepared data, blocking if necessary.
         *
         * @return prepared {@link Data}
         */
        Data await() {
            try {
                return future.get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CancellationException("Molecule preparation interrupted");
            } catch (ExecutionException failure) {
                throw new IllegalStateException("Cannot prepare molecule geometry " + key.molecule.id(), failure.getCause());
            }
        }
    }

    /** Private constructor to prevent instantiation of utility class. */
    private PreparedMoleculeGeometry() {}

    /**
     * Requests prepared molecule geometry with default styling.
     *
     * @param molecule  molecule structure
     * @param cubic     whether geometry is cubic
     * @param palette   element palette snapshot
     * @param instanced whether geometry is instanced
     * @return geometry {@link Job}, or {@code null} if cache limits are exceeded
     */
    static Job request(MoleculeStructure molecule, boolean cubic, ElementPalette.Snapshot palette, boolean instanced) {
        return request(molecule, cubic, palette, instanced, MoleculeStyle.DEFAULT);
    }

    /**
     * Requests prepared molecule geometry with specified styling.
     *
     * @param molecule  molecule structure
     * @param cubic     whether geometry is cubic
     * @param palette   element palette snapshot
     * @param instanced whether geometry is instanced
     * @param style     molecule render style
     * @return geometry {@link Job}, or {@code null} if cache limits are exceeded
     */
    static Job request(MoleculeStructure molecule, boolean cubic, ElementPalette.Snapshot palette, boolean instanced,
                       MoleculeStyle style) {
        Key key = new Key(molecule, !instanced && cubic, palette.revision(), instanced, style);
        Job existing = JOBS.get(key);
        if (existing != null) return existing;
        long bytes = estimate(molecule, cubic, instanced);
        if (bytes > MAX_BYTES) throw new IllegalArgumentException("Molecule geometry exceeds the 128 MiB preparation limit");
        var oldest = JOBS.values().iterator();
        while ((JOBS.size() >= MAX_JOBS || reserved + bytes > MAX_BYTES) && oldest.hasNext()) {
            Job job = oldest.next();
            if (!job.future.isDone()) continue;
            reserved -= job.bytes;
            oldest.remove();
        }
        if (JOBS.size() >= MAX_JOBS || reserved + bytes > MAX_BYTES) return null;
        Future<Data> future = GeometryPreparation.submit(() -> prepare(molecule, cubic, palette, instanced, style));
        if (future == null) return null;
        Job job = new Job(key, bytes, future);
        JOBS.put(key, job);
        reserved += bytes;
        return job;
    }

    /**
     * Releases a completed or active geometry job from cache.
     *
     * @param job target job
     */
    static void release(Job job) {
        if (JOBS.remove(job.key, job)) reserved -= job.bytes;
    }

    /** Clears all cached geometry jobs and resets reserved byte counts. */
    static void clear() {
        JOBS.values().forEach(job -> job.future.cancel(true));
        JOBS.clear();
        reserved = 0;
    }

    /**
     * Prepares molecule geometry data synchronously with default style.
     *
     * @param molecule  molecule structure
     * @param cubic     whether geometry is cubic
     * @param palette   element palette snapshot
     * @param instanced whether geometry is instanced
     * @return prepared geometry {@link Data}
     */
    static Data prepare(MoleculeStructure molecule, boolean cubic, ElementPalette.Snapshot palette, boolean instanced) {
        return prepare(molecule, cubic, palette, instanced, MoleculeStyle.DEFAULT);
    }

    /**
     * Prepares molecule geometry data synchronously with specified style.
     *
     * @param molecule  molecule structure
     * @param cubic     whether geometry is cubic
     * @param palette   element palette snapshot
     * @param instanced whether geometry is instanced
     * @param style     molecule render style
     * @return prepared geometry {@link Data}
     */
    static Data prepare(MoleculeStructure molecule, boolean cubic, ElementPalette.Snapshot palette, boolean instanced,
                        MoleculeStyle style) {
        GeometryPreparation.checkCancelled();
        if (instanced) {
            MoleculeInstances instances = new MoleculeInstances(molecule, palette, style);
            ByteBuffer atoms = floats(instances.atoms), bonds = floats(instances.bonds);
            return new Data(atoms, bonds, instances.atoms.length / MoleculeInstances.ATOM_STRIDE,
                    instances.bonds.length / MoleculeInstances.BOND_STRIDE, true);
        }
        BufferBuilder buffer = new BufferBuilder(8192);
        buffer.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION_COLOR);
        MoleculeRenderer.appendTransformedGeometry(buffer, molecule, new Matrix4f(), cubic, palette, style);
        buffer.finishDrawing();
        GeometryPreparation.checkCancelled();
        ByteBuffer vertices = BufferUtils.createByteBuffer(buffer.getByteBuffer().remaining());
        vertices.put(buffer.getByteBuffer()).flip();
        return new Data(readOnly(vertices), readOnly(BufferUtils.createByteBuffer(0)), buffer.getVertexCount(), 0, false);
    }

    /**
     * Converts a float array into a read-only native-ordered byte buffer.
     *
     * @param values float array
     * @return ByteBuffer instance
     */
    private static ByteBuffer floats(float[] values) {
        ByteBuffer result = BufferUtils.createByteBuffer(values.length * Float.BYTES);
        for (int i = 0; i < values.length; i++) {
            if ((i & 4095) == 0) GeometryPreparation.checkCancelled();
            result.putFloat(values[i]);
        }
        return readOnly(result.flip());
    }

    /**
     * Wraps a byte buffer into a read-only native-ordered view.
     *
     * @param buffer source byte buffer
     * @return read-only byte buffer
     */
    private static ByteBuffer readOnly(ByteBuffer buffer) {
        return buffer.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
    }

    /**
     * Estimates memory size required for a molecule structure's prepared geometry.
     *
     * @param molecule  molecule structure
     * @param cubic     whether geometry is cubic
     * @param instanced whether geometry is instanced
     * @return estimated byte count
     */
    private static long estimate(MoleculeStructure molecule, boolean cubic, boolean instanced) {
        long atoms = molecule.atoms().size(), bonds = molecule.bonds().size();
        if (instanced) return (atoms * MoleculeInstances.ATOM_STRIDE * 4L) + (bonds * MoleculeInstances.BOND_STRIDE * 4L * 3L);
        // Each multiple bond has at most three cylinders. Degenerate bonds use less.
        return (atoms * (cubic ? 36 : 840) + bonds * (cubic ? 24 : 60) * 3) * 16;
    }
}
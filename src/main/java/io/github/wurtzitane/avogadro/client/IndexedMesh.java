package io.github.wurtzitane.avogadro.client;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import org.lwjgl.BufferUtils;

/**
 * Handles bit-exact vertex deduplication and indexing for meshes.
 * <p>
 * Bit-exact vertex deduplication: all attributes participate, including shading boundaries.
 */
final class IndexedMesh {
    final ByteBuffer vertices;
    final IntBuffer indices;

    /**
     * Creates a indexed mesh from raw vertex input and stride.
     *
     * @param input raw source vertex byte buffer
     * @param stride vertex stride in bytes
     */
    IndexedMesh(ByteBuffer input, int stride) {
        ByteBuffer source = input.duplicate().order(input.order());
        if (stride <= 0 || stride % 4 != 0 || source.remaining() % stride != 0) {
            throw new IllegalArgumentException("Invalid vertex stride");
        }
        int count = source.remaining() / stride;
        ByteBuffer packed = BufferUtils.createByteBuffer(source.remaining());
        indices = BufferUtils.createIntBuffer(count);
        int capacity = 1;
        while (capacity < Math.max(2L, (long) count * 2)) {
            if (capacity >= 1 << 29) {
                throw new IllegalArgumentException("Mesh is too large");
            }
            capacity <<= 1;
        }
        int[] slots = new int[capacity];
        int unique = 0;
        for (int i = 0, start = source.position(); i < count; i++) {
            int offset = start + i * stride;
            int hash = 1;
            for (int w = 0; w < stride; w += 4) {
                hash = 31 * hash + source.getInt(offset + w);
            }
            int slot = (hash ^ (hash >>> 16)) & (capacity - 1);
            while (slots[slot] != 0 && !equal(source, offset, packed, (slots[slot] - 1) * stride, stride)) {
                slot = (slot + 1) & (capacity - 1);
            }
            int index;
            if (slots[slot] == 0) {
                index = unique++;
                slots[slot] = index + 1;
                for (int w = 0; w < stride; w += 4) {
                    packed.putInt(source.getInt(offset + w));
                }
            } else {
                index = slots[slot] - 1;
            }
            indices.put(index);
        }
        vertices = packed.flip();
        indices.flip();
    }

    /** Compares two vertices bit-exact for equality across the given stride. */
    private static boolean equal(ByteBuffer a, int first, ByteBuffer b, int second, int stride) {
        for (int w = 0; w < stride; w += 4) {
            if (a.getInt(first + w) != b.getInt(second + w)) {
                return false;
            }
        }
        return true;
    }
}

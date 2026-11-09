package io.github.wurtzitane.avogadro.molecule;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import net.minecraft.util.ResourceLocation;

/** Protocol 3 carries raw JSON and requires chirality-aware clients. */
public final class MoleculeSync {

    /** Protocol version integer identifier. */
    public static final int PROTOCOL = 3;

    /** Maximum allowed byte size for compressed snapshots (8 MiB). */
    public static final int MAX_BYTES = 8 * 1024 * 1024;

    /** Maximum allowed entry count for synchronization maps. */
    public static final int MAX_ENTRIES = 8192;

    /** Maximum allowed byte size for resource location identifiers on the wire. */
    private static final int MAX_IDENTIFIER_BYTES = 1024;

    private MoleculeSync() {}

    /**
     * Encodes molecule resource data into a compressed byte array using protocol 3.
     *
     * @param data molecule resource data instance
     * @return compressed byte array payload
     * @throws IOException if encoding or compression exceeds size limits
     */
    public static byte[] encode(MoleculeResources.Data data) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        // Count uncompressed bytes while streaming, without retaining a second full snapshot.
        try (DataOutputStream out = new DataOutputStream(
                new LimitedOutputStream(new BufferedOutputStream(new GZIPOutputStream(compressed))))) {
            out.writeInt(PROTOCOL);
            writeMap(out, data.molecules());
            writeMap(out, data.itemTags());
            writeMap(out, data.fluidTags());
        }
        if (compressed.size() > MAX_BYTES) {
            throw new IOException("Compressed snapshot exceeds 8 MiB");
        }
        return compressed.toByteArray();
    }

    /**
     * Decodes a compressed byte array payload into molecule resource data.
     *
     * @param bytes compressed byte array payload
     * @return decoded {@link MoleculeResources.Data}
     * @throws IOException if protocol version is unsupported, bounds are violated, or decompression fails
     */
    public static MoleculeResources.Data decode(byte[] bytes) throws IOException {
        if (bytes.length > MAX_BYTES) {
            throw new IOException("Compressed snapshot exceeds limit");
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new LimitedInputStream(new GZIPInputStream(new ByteArrayInputStream(bytes)))))) {
            int remoteProtocol = in.readInt();
            if (remoteProtocol != PROTOCOL) {
                throw new IOException("Incompatible Avogadro data protocol " + remoteProtocol + " (expected " + PROTOCOL + "); install the same Avogadro build on client and server");
            }
            MoleculeResources.Data result = new MoleculeResources.Data(readMap(in), readMap(in), readMap(in));
            if (in.read() != -1) {
                throw new IOException("Trailing synchronization data");
            }
            return result;
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid synchronization identifier", e);
        }
    }

    /**
     * Writes a resource location to string map into a data output stream.
     *
     * @param out data output stream
     * @param map map of resource locations to strings
     * @throws IOException if entry limits are exceeded or writing fails
     */
    private static void writeMap(DataOutputStream out, Map<ResourceLocation, String> map) throws IOException {
        if (map.size() > MAX_ENTRIES) {
            throw new IOException("Too many molecule/tag definitions");
        }
        out.writeInt(map.size());
        for (var entry : map.entrySet()) {
            writeString(out, entry.getKey().toString(), MAX_IDENTIFIER_BYTES);
            writeString(out, entry.getValue(), MoleculeResources.MAX_FILE_BYTES);
        }
    }

    /**
     * Reads a resource location to string map from a data input stream.
     *
     * @param in data input stream
     * @return parsed map of resource locations to strings
     * @throws IOException if entry counts are invalid or duplicate IDs occur
     */
    private static Map<ResourceLocation, String> readMap(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAX_ENTRIES) {
            throw new IOException("Invalid entry count: " + count);
        }
        Map<ResourceLocation, String> map = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            ResourceLocation id = MoleculeLoader.identifier(readString(in, MAX_IDENTIFIER_BYTES));
            if (map.put(id, readString(in, MoleculeResources.MAX_FILE_BYTES)) != null) {
                throw new IOException("Duplicate id " + id);
            }
        }
        return map;
    }

    /**
     * Writes a length-prefixed UTF-8 string to a data output stream.
     *
     * @param out data output stream
     * @param value string value
     * @param max maximum allowed byte length
     * @throws IOException if string exceeds wire limits
     */
    private static void writeString(DataOutputStream out, String value, int max) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > max) {
            throw new IOException("String exceeds wire limit");
        }
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    /**
     * Reads a length-prefixed UTF-8 string from a data input stream.
     *
     * @param in data input stream
     * @param max maximum allowed byte length
     * @return decoded string value
     * @throws IOException if size is invalid or decoding fails
     */
    private static String readString(DataInputStream in, int max) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > max) {
            throw new IOException("Invalid string size");
        }
        byte[] bytes = new byte[size];
        in.readFully(bytes);
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    /** Filter input stream enforcing strict byte count limits during decompression. */
    private static final class LimitedInputStream extends FilterInputStream {

        /** Current read byte count. */
        private int count;

        /**
         * Constructs a limited input stream with a source input stream.
         *
         * @param source source input stream
         */
        LimitedInputStream(InputStream source) {
            super(source);
        }

        /** {@inheritDoc} */
        @Override
        public int read() throws IOException {
            int value = in.read();
            if (value >= 0 && ++count > MAX_BYTES)
                throw new IOException("Expanded snapshot exceeds limit");
            return value;
        }

        /** {@inheritDoc} */
        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = in.read(bytes, offset, Math.min(length, MAX_BYTES - count + 1));
            if (read > 0) {
                count += read;
                if (count > MAX_BYTES)
                    throw new IOException("Expanded snapshot exceeds limit");
            }
            return read;
        }
    }

    /** Filter output stream enforcing strict byte count limits during compression. */
    private static final class LimitedOutputStream extends FilterOutputStream {

        /** Current written byte count. */
        private int count;

        /**
         * Constructs a limited output stream with a target output stream.
         *
         * @param target target output stream
         */
        LimitedOutputStream(OutputStream target) {
            super(target);
        }

        /**
         * Reserves and validates byte amounts against maximum size limits.
         *
         * @param amount byte amount to reserve
         * @throws IOException if limit is exceeded
         */
        private void reserve(int amount) throws IOException {
            if (amount > MAX_BYTES - count) {
                throw new IOException("Molecule snapshot exceeds 8 MiB");
            }
            count += amount;
        }

        /** {@inheritDoc} */
        @Override
        public void write(int value) throws IOException {
            reserve(1);
            out.write(value);
        }

        /** {@inheritDoc} */
        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            reserve(length);
            out.write(bytes, offset, length);
        }
    }
}

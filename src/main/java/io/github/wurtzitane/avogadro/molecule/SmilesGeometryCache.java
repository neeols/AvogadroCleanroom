package io.github.wurtzitane.avogadro.molecule;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Exact derived geometry only. A missing or damaged disk cache never rejects chemistry. */
public final class SmilesGeometryCache {
    /** Semantic version revision string used to invalidate cache entries when parsing or element logic changes. */
    // Bump when parser interpretation, solver arithmetic, or AtomElement defaults change.
    static final String SEMANTICS = "smiles-layout-1-elements-1";

    /** Volatile SMILES geometry engine instance managing memory and disk caching. */
    private static volatile Engine cache = new Engine(null, 32L << 20, 256L << 20, 4096);

    /** Private constructor to prevent instantiation of utility class. */
    private SmilesGeometryCache() {}

    /**
     * Initializes the SMILES geometry cache engine with a specified storage directory.
     *
     * @param directory target caching directory path
     */
    public static synchronized void initialize(Path directory) {
        Engine previous = cache;
        cache = new Engine(directory, 32L << 20, 256L << 20, 4096);
        previous.close();
    }

    /**
     * Retrieves or computes a cached molecule structure for the given SMILES string and locale.
     *
     * @param id          structure resource identifier string
     * @param smiles      SMILES chemical structure string
     * @param locale      locale instance
     * @param computation supplier function to compute the structure if not cached
     * @return constructed {@link MoleculeStructure} instance
     */
    static MoleculeStructure get(String id, String smiles, Locale locale, Supplier<MoleculeStructure> computation) {
        return cache.get(new Key(SEMANTICS, locale.toLanguageTag(), smiles), computation).withId(id);
    }

    /**
     * Cache key record capturing semantic revision, locale tag, and SMILES string.
     *
     * @param revision semantic version string
     * @param locale   locale tag string
     * @param smiles   SMILES string
     */
    record Key(String revision, String locale, String smiles) {}

    /**
     * Cache value record holding a molecule structure and its calculated memory weight in bytes.
     *
     * @param molecule molecule structure instance
     * @param bytes    memory consumption in bytes
     */
    private record Value(MoleculeStructure molecule, long bytes) {}

    /** Caching engine handling memory LRU maps, disk serialization, concurrency, and file locking. */
    static final class Engine implements AutoCloseable {

        /** Magic integer header identifier for cache files. */
        private static final int MAGIC = 0x4156534D;

        /** File format version integer. */
        private static final int FORMAT = 1;

        /** Header byte size for cache files. */
        private static final int HEADER = 48;

        /** Storage directory path, or {@code null}. */
        final Path directory;

        /** Maximum allowed memory storage limit and disk storage limit in bytes. */
        final long memoryLimit, diskLimit;

        /** Maximum allowed cache entry limit count. */
        final int entryLimit;

        /** Linked hash map maintaining memory LRU cache order. */
        final LinkedHashMap<Key, Value> memory = new LinkedHashMap<>(16, .75f, true);

        /** Map tracking in-flight asynchronous computation futures to prevent duplicate work. */
        final Map<Key, CompletableFuture<MoleculeStructure>> pending = new HashMap<>();

        /** Current memory bytes used and queued disk persistence bytes. */
        long bytes, queuedBytes;

        /** Timestamp of the last logged warning message. */
        private long lastWarning;

        /** Dedicated single-thread executor for asynchronous disk writes. */
        private final ThreadPoolExecutor writer;

        /**
         * Constructs a caching engine with specified storage directories, limits, and entry counts.
         *
         * @param directory   caching directory path
         * @param memoryLimit maximum memory byte limit
         * @param diskLimit   maximum disk byte limit
         * @param entryLimit  maximum entry count limit
         */
        Engine(Path directory, long memoryLimit, long diskLimit, int entryLimit) {
            this.directory = directory == null ? null : directory.toAbsolutePath().normalize();
            this.memoryLimit = memoryLimit; this.diskLimit = diskLimit; this.entryLimit = entryLimit;
            writer = new ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS, new ArrayBlockingQueue<>(4), action -> {
                Thread thread = new Thread(action, "Avogadro SMILES cache");
                thread.setDaemon(true); thread.setPriority(Thread.NORM_PRIORITY - 1); return thread;
            });
            writer.allowCoreThreadTimeOut(true);
        }

        /**
         * Retrieves a cached structure or computes, caches, and persists it.
         *
         * @param key         cache key instance
         * @param computation computation supplier function
         * @return resolved {@link MoleculeStructure} instance
         */
        MoleculeStructure get(Key key, Supplier<MoleculeStructure> computation) {
            while (true) {
                MoleculeTasks.checkCancelled();
                CompletableFuture<MoleculeStructure> flight;
                boolean owner;
                synchronized (this) {
                    Value hit = memory.get(key);
                    if (hit != null) { MoleculeTasks.checkCancelled(); return hit.molecule; }
                    flight = pending.get(key);
                    owner = flight == null;
                    if (owner) { flight = new CompletableFuture<>(); pending.put(key, flight); }
                }
                if (!owner) {
                    try { MoleculeStructure result = flight.get(); MoleculeTasks.checkCancelled(); return result; }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt(); throw new CancellationException("SMILES cache wait cancelled");
                    } catch (CancellationException cancelledOwner) { continue; }
                    catch (ExecutionException failure) {
                        if (failure.getCause() instanceof CancellationException) continue;
                        throw propagate(failure.getCause());
                    }
                }
                try {
                    MoleculeStructure molecule = read(key);
                    boolean computed = molecule == null;
                    if (computed) molecule = computation.get();
                    MoleculeTasks.checkCancelled();
                    put(key, molecule);
                    flight.complete(molecule);
                    if (computed) persist(key, molecule);
                    return molecule;
                } catch (Throwable failure) {
                    // Remove first: waking waiters may immediately retry a cancelled owner.
                    synchronized (this) { pending.remove(key, flight); }
                    flight.completeExceptionally(failure);
                    throw propagate(failure);
                } finally {
                    synchronized (this) { pending.remove(key, flight); }
                }
            }
        }

        /**
         * Propagates captured execution exceptions as runtime exceptions or errors.
         *
         * @param failure failure throwable instance
         * @return propagated runtime exception
         */
        private static RuntimeException propagate(Throwable failure) {
            if (failure instanceof RuntimeException runtime) return runtime;
            if (failure instanceof Error error) throw error;
            return new IllegalStateException(failure);
        }

        /**
         * Calculates the estimated memory weight in bytes for a cache entry.
         *
         * @param key      cache key instance
         * @param molecule molecule structure instance
         * @return calculated byte weight
         */
        static long weight(Key key, MoleculeStructure molecule) {
            return 512L + 2L * (key.smiles().length() + key.locale().length() + key.revision().length())
                    + 96L * molecule.atoms().size() + 32L * molecule.bonds().size();
        }

        /**
         * Stores a molecule structure in the memory cache, evicting older entries if limits are exceeded.
         *
         * @param key      cache key instance
         * @param molecule molecule structure instance
         */
        synchronized void put(Key key, MoleculeStructure molecule) {
            long cost = weight(key, molecule);
            if (cost > memoryLimit || entryLimit == 0) return;
            Value old = memory.remove(key);
            if (old != null) bytes -= old.bytes;
            memory.put(key, new Value(molecule, cost)); bytes += cost;
            var iterator = memory.entrySet().iterator();
            while ((bytes > memoryLimit || memory.size() > entryLimit) && iterator.hasNext()) {
                bytes -= iterator.next().getValue().bytes; iterator.remove();
            }
        }

        /**
         * Reads and validates a cached molecule structure from disk storage.
         *
         * @param key cache key instance
         * @return loaded {@link MoleculeStructure} instance, or {@code null} if cache miss or invalid
         */
        private MoleculeStructure read(Key key) {
            if (directory == null) return null;
            try {
                if (!Files.isDirectory(directory)) return null;
                try (FileChannel channel = FileChannel.open(directory.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                     FileLock lock = channel.tryLock()) {
                    if (lock == null) return null;
                    Path file = directory.resolve(filename(key));
                    if (!Files.isRegularFile(file)) return null;
                    long size = Files.size(file);
                    if (size < HEADER || size > diskLimit) return null;
                    try (var raw = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
                        if (raw.readInt() != MAGIC || raw.readInt() != FORMAT) return null;
                        long length = raw.readLong();
                        if (length != size - HEADER) return null;
                        byte[] expected = raw.readNBytes(32);
                        MessageDigest digest = digest();
                        var input = new DataInputStream(new DigestInputStream(raw, digest));
                        if (!matchesString(input, key.revision) || !matchesString(input, key.locale) || !matchesString(input, key.smiles)) return null;
                        int atoms = input.readInt(), bonds = input.readInt();
                        if (atoms <= 0 || bonds < 0 || 18L * atoms + 12L * bonds > length
                                || 512L + 96L * atoms + 32L * bonds + 2L * key.smiles().length() > (32L << 20)) return null;
                        var builder = MoleculeStructure.builder("avogadro:cached-smiles", "", atoms, bonds);
                        for (int i = 0; i < atoms; i++) {
                            if ((i & 1023) == 0) MoleculeTasks.checkCancelled();
                            String symbol = readString(input, Math.min(length, 256));
                            float x = Float.intBitsToFloat(input.readInt()), y = Float.intBitsToFloat(input.readInt()), z = Float.intBitsToFloat(input.readInt());
                            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) return null;
                            builder.addAtom(AtomElement.bySymbol(symbol), x, y, z);
                        }
                        BondType[] types = BondType.values();
                        for (int i = 0; i < bonds; i++) {
                            if ((i & 1023) == 0) MoleculeTasks.checkCancelled();
                            int from = input.readInt(), to = input.readInt(), type = input.readInt();
                            if (from < 0 || from >= atoms || to < 0 || to >= atoms || from == to || type < 0 || type >= types.length) return null;
                            builder.bond(from, to, types[type]);
                        }
                        if (input.read() != -1 || !MessageDigest.isEqual(expected, digest.digest())) return null;
                        MoleculeStructure result = builder.build();
                        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()));
                        return result;
                    }
                }
            } catch (CancellationException cancelled) { throw cancelled; }
            catch (java.nio.channels.OverlappingFileLockException busy) { return null; }
            catch (IOException | RuntimeException failure) { warn(failure); return null; }
        }

        /**
         * Queues asynchronous persistence of a molecule structure to disk.
         *
         * @param key      cache key instance
         * @param molecule molecule structure instance
         */
        private void persist(Key key, MoleculeStructure molecule) {
            if (directory == null || molecule.atoms().isEmpty()) return;
            long cost = weight(key, molecule);
            synchronized (this) {
                if (cost > memoryLimit || cost > diskLimit || queuedBytes + cost > memoryLimit) return;
                queuedBytes += cost;
            }
            try {
                writer.execute(() -> {
                    try { write(key, molecule); }
                    finally { synchronized (this) { queuedBytes -= cost; } }
                });
            } catch (RejectedExecutionException full) { synchronized (this) { queuedBytes -= cost; } }
        }

        /**
         * Serializes and writes a molecule structure to a temporary disk file, then atomically moves it into place.
         *
         * @param key      cache key instance
         * @param molecule molecule structure instance
         */
        private void write(Key key, MoleculeStructure molecule) {
            Path temporary = null;
            try {
                Files.createDirectories(directory);
                try (FileChannel channel = FileChannel.open(directory.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                     FileLock lock = channel.tryLock()) {
                    if (lock == null) return;
                    long expected = encodedSize(key, molecule) + HEADER;
                    if (expected > diskLimit || entryLimit == 0) return;
                    prune(expected);
                    temporary = Files.createTempFile(directory, ".pending-", ".tmp");
                    MessageDigest digest = digest();
                    try (var file = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                        file.writeInt(MAGIC); file.writeInt(FORMAT); file.writeLong(expected - HEADER); file.write(new byte[32]);
                        var output = new DataOutputStream(new DigestOutputStream(file, digest));
                        writeString(output, key.revision()); writeString(output, key.locale()); writeString(output, key.smiles());
                        output.writeInt(molecule.atoms().size()); output.writeInt(molecule.bonds().size());
                        for (var atom : molecule.atoms()) {
                            writeString(output, atom.element().symbol());
                            output.writeInt(Float.floatToRawIntBits(atom.x())); output.writeInt(Float.floatToRawIntBits(atom.y())); output.writeInt(Float.floatToRawIntBits(atom.z()));
                        }
                        for (var bond : molecule.bonds()) { output.writeInt(bond.from()); output.writeInt(bond.to()); output.writeInt(bond.type().ordinal()); }
                        output.flush();
                    }
                    try (var file = new RandomAccessFile(temporary.toFile(), "rw")) { file.seek(16); file.write(digest.digest()); }
                    Path target = directory.resolve(filename(key));
                    try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                    catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
                    temporary = null;
                }
            } catch (java.nio.channels.OverlappingFileLockException busy) { /* Another process or cache reader owns this disposable write. */ }
            catch (IOException | RuntimeException failure) { warn(failure); }
            finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) {} }
        }

        /**
         * Prunes old disk cache files to enforce disk storage and entry limits.
         *
         * @param incoming byte size of incoming entry
         * @throws IOException if directory listing or file deletion fails
         */
        private void prune(long incoming) throws IOException {
            List<Path> files;
            try (var stream = Files.list(directory)) {
                files = new ArrayList<>(stream.filter(file -> file.getFileName().toString().matches("[0-9a-f]{64}\\.bin|\\.pending-.*\\.tmp")).toList());
            }
            files.sort(Comparator.comparingLong(file -> { try { return Files.getLastModifiedTime(file).toMillis(); } catch (IOException ignored) { return Long.MIN_VALUE; } }));
            long total = 0;
            for (Path file : files) total += Files.size(file);
            int count = files.size();
            for (Path file : files) {
                if (total + incoming <= diskLimit && count < entryLimit) break;
                long size = Files.size(file); Files.deleteIfExists(file); total -= size; count--;
            }
        }

        /**
         * Computes the encoded byte size for a cache key and molecule structure.
         *
         * @param key      cache key instance
         * @param molecule molecule structure instance
         * @return estimated encoded size in bytes
         */
        private static long encodedSize(Key key, MoleculeStructure molecule) {
            long result = 20L + 2L * (key.revision().length() + key.locale().length() + key.smiles().length()) + 12L * molecule.bonds().size();
            for (var atom : molecule.atoms()) result += 16L + 2L * atom.element().symbol().length();
            return result;
        }

        /**
         * Creates a SHA-256 message digest instance.
         *
         * @return {@link MessageDigest} instance
         */
        private static MessageDigest digest() { try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); } }

        /**
         * Generates a hexadecimal filename hash string for a cache key.
         *
         * @param key cache key instance
         * @return hashed filename string with a {@code .bin} extension
         */
        private static String filename(Key key) {
            try {
                MessageDigest digest = digest();
                var output = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest));
                writeString(output, key.revision()); writeString(output, key.locale()); writeString(output, key.smiles()); output.flush();
                return HexFormat.of().formatHex(digest.digest()) + ".bin";
            } catch (IOException impossible) { throw new AssertionError(impossible); }
        }

        /**
         * Writes a length-prefixed string to a data output stream.
         *
         * @param output data output stream
         * @param text   string value
         * @throws IOException if writing fails
         */
        private static void writeString(DataOutputStream output, String text) throws IOException { output.writeInt(text.length()); output.writeChars(text); }

        /**
         * Verifies whether an input stream string matches an expected string.
         *
         * @param input    data input stream
         * @param expected expected string value
         * @return {@code true} if strings match, {@code false} otherwise
         * @throws IOException if reading fails
         */
        private static boolean matchesString(DataInputStream input, String expected) throws IOException {
            if (input.readInt() != expected.length()) return false;
            for (int i = 0; i < expected.length(); i++) if (input.readChar() != expected.charAt(i)) return false;
            return true;
        }

        /**
         * Reads a length-prefixed string from a data input stream with boundary checks.
         *
         * @param input     data input stream
         * @param remaining remaining buffer byte count
         * @return decoded string value
         * @throws IOException if length is invalid or exceeds bounds
         */
        private static String readString(DataInputStream input, long remaining) throws IOException {
            int length = input.readInt();
            if (length < 0 || 2L * length + 4 > remaining || 2L * length > input.available()) throw new IOException("Invalid SMILES cache string length");
            char[] chars = new char[length]; for (int i = 0; i < length; i++) chars[i] = input.readChar(); return new String(chars);
        }

        /**
         * Rate-limits and logs caching warnings.
         *
         * @param failure exception instance
         */
        private synchronized void warn(Exception failure) {
            long now = System.nanoTime();
            if (lastWarning != 0 && now - lastWarning < TimeUnit.MINUTES.toNanos(1)) return;
            lastWarning = now;
            org.apache.logging.log4j.LogManager.getLogger("avogadro").warn("SMILES cache unavailable; recomputing geometry: {}", failure.toString());
        }

        /**
         * Flushes pending background disk write operations.
         *
         * @throws InterruptedException if thread is interrupted
         * @throws ExecutionException   if execution fails
         * @throws TimeoutException     if timeout occurs
         */
        void flush() throws InterruptedException, ExecutionException, TimeoutException {
            writer.submit(() -> {}).get(30, TimeUnit.SECONDS);
        }

        /** {@inheritDoc} */
        @Override public void close() { writer.shutdown(); }
    }
}
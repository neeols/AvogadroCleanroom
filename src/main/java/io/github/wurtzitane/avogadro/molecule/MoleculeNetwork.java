package io.github.wurtzitane.avogadro.molecule;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

import io.github.wurtzitane.avogadro.Avogadro;
import io.netty.buffer.ByteBuf;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.handshake.NetworkDispatcher;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

/**
 * Handles network synchronization and chunked packet assembly for molecule resource data across clients and servers.
 */
public final class MoleculeNetwork {

    /** Maximum payload byte size per chunk transmission (24 KB). */
    private static final int CHUNK_BYTES = 24 * 1024;

    /** Total header byte size for packet serialization. */
    private static final int HEADER_BYTES = Long.BYTES + 3 * Integer.BYTES;

    /** Maximum allowed chunk count based on maximum total bytes and chunk size. */
    private static final int MAX_CHUNKS = (MoleculeSync.MAX_BYTES + CHUNK_BYTES - 1) / CHUNK_BYTES;

    /** Simple network wrapper channel instance for the Avogadro mod. */
    private static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel(Avogadro.MODID);

    /** Atomic sequence counter for tracking packet revision numbers. */
    private static final AtomicLong SEQUENCE = new AtomicLong();

    /** Thread-safe assembler instance for incoming client packet chunks. */
    private static final Assembler ASSEMBLER = new Assembler();

    private MoleculeNetwork() {}

    /** Registers network message handlers and packet channels on the client side. */
    public static void register() {
        CHANNEL.registerMessage(Handler.class, Chunk.class, 0, Side.CLIENT);
    }

    /** Resets the client-side assembler state safely within a synchronization block. */
    public static void resetClient() {
        synchronized (ASSEMBLER) {
            ASSEMBLER.reset();
        }
    }

    /**
     * Sends encoded molecule resource data to a specific player if they have the client mod installed.
     *
     * @param player target player entity
     * @param data molecule resource data instance
     */
    public static void sendTo(EntityPlayerMP player, MoleculeResources.Data data) {
        if (!hasClientMod(player))
            return;
        try {
            sendChunks(player, MoleculeSync.encode(data));
        } catch (IOException e) {
            Avogadro.LOGGER.error("Cannot synchronize molecules to {}: {}", player.getName(), e.getMessage());
        }
    }

    /**
     * Sends a raw byte payload to a specific player if they have the client mod installed.
     *
     * @param player target player entity
     * @param payload encoded byte array payload
     */
    static void sendTo(EntityPlayerMP player, byte[] payload) {
        if (hasClientMod(player)) {
            sendChunks(player, payload);
        }
    }

    /**
     * Checks whether the specified player has the Avogadro client mod installed via network dispatchers.
     *
     * @param player player entity to check
     * @return {@code true} if the client mod is present, {@code false} otherwise
     */
    private static boolean hasClientMod(EntityPlayerMP player) {
        NetworkDispatcher dispatcher = NetworkDispatcher.get(player.connection.netManager);
        return dispatcher != null && dispatcher.getModList().containsKey(Avogadro.MODID);
    }

    /**
     * Splits a byte payload into indexed chunks and sends them to the target player.
     *
     * @param player target player entity
     * @param payload byte array payload to chunk
     */
    private static void sendChunks(EntityPlayerMP player, byte[] payload) {
        long revision = SEQUENCE.incrementAndGet();
        int count = (payload.length + CHUNK_BYTES - 1) / CHUNK_BYTES;
        for (int index = 0; index < count; index++) {
            int start = index * CHUNK_BYTES;
            int end = Math.min(payload.length, start + CHUNK_BYTES);
            CHANNEL.sendTo(new Chunk(revision, index, count, payload, start, end - start), player);
        }
    }

    /** Represents a transmission chunk packet used during molecule synchronization. */
    public static final class Chunk implements IMessage {

        /** Revision number identifying the transfer session. */
        long revision;

        /** Index of the current chunk. */
        int index;

        /** Total number of chunks in the transfer. */
        int count;

        /** Byte array payload data. */
        byte[] payload;

        /** Offset within the payload array. */
        int offset;

        /** Length of bytes in this chunk. */
        int length;

        /** Default constructor required for network instantiation. */
        public Chunk() {}

        /**
         * Constructs a chunk spanning an entire payload array.
         *
         * @param revision transfer revision ID
         * @param index chunk index
         * @param count total chunk count
         * @param payload payload byte array
         */
        Chunk(long revision, int index, int count, byte[] payload) {
            this(revision, index, count, payload, 0, payload.length);
        }

        /**
         * Outbound snapshot storage is immutable for the lifetime of queued messages.
         *
         * @param revision transfer revision ID
         * @param index chunk index
         * @param count total chunk count
         * @param payload payload byte array
         * @param offset array offset
         * @param length chunk length
         */
        Chunk(long revision, int index, int count, byte[] payload, int offset, int length) {
            this.revision = revision;
            this.index = index;
            this.count = count;
            this.payload = payload;
            this.offset = offset;
            this.length = length;
        }

        /**
         * Serializes chunk data into a Netty byte buffer.
         *
         * @param buffer target byte buffer
         */
        @Override
        public void toBytes(ByteBuf buffer) {
            buffer.writeLong(revision);
            buffer.writeInt(index);
            buffer.writeInt(count);
            buffer.writeInt(length);
            buffer.writeBytes(payload, offset, length);
        }

        /**
         * Deserializes chunk data from a Netty byte buffer, enforcing strict boundary checks.
         *
         * @param buffer source byte buffer
         * @throws IllegalArgumentException if packet bounds or headers are invalid
         */
        @Override
        public void fromBytes(ByteBuf buffer) {
            if (buffer.readableBytes() < HEADER_BYTES) {
                throw new IllegalArgumentException("Truncated Avogadro packet");
            }
            revision = buffer.readLong();
            index = buffer.readInt();
            count = buffer.readInt();
            int length = buffer.readInt();
            if (count < 1 || count > MAX_CHUNKS || index < 0 || index >= count || length < 1 || length > CHUNK_BYTES
                    || buffer.readableBytes() != length) {
                throw new IllegalArgumentException("Invalid Avogadro packet bounds");
            }
            payload = new byte[length];
            buffer.readBytes(payload);
            this.offset = 0;
            this.length = length;
        }
    }

    /** Message handler for incoming chunk packets on the client side. */
    public static final class Handler implements IMessageHandler<Chunk, IMessage> {

        /**
         * Handles incoming chunk packets, reassembling complete molecule data or catching errors.
         *
         * @param message incoming chunk message
         * @param context message context instance
         * @return response message, or {@code null}
         */
        @Override
        public IMessage onMessage(Chunk message, MessageContext context) {
            try {
                MoleculeResources.Data data;
                synchronized (ASSEMBLER) {
                    data = ASSEMBLER.accept(message);
                }
                if (data != null) {
                    Avogadro.proxy.applyServerData(data);
                }
            } catch (IOException | IllegalArgumentException e) {
                resetClient();
                Avogadro.LOGGER.error("Rejected molecule synchronization: {}", e.getMessage());
            }
            return null;
        }
    }

    /** A partial or invalid transfer never replaces the client's current snapshot. */
    static final class Assembler {

        /** Current transfer revision ID (-1 if inactive). */
        private long revision = -1;

        /** Expected next chunk index. */
        private int next;

        /** Total expected chunk count. */
        private int count;

        /** Byte array output stream buffer for accumulating chunk data. */
        private ByteArrayOutputStream buffer;

        /** Resets the assembler state completely. */
        void reset() {
            revision = -1;
            next = 0;
            count = 0;
            buffer = null;
        }

        /**
         * Accepts an incoming chunk, validating sequence order and size limits.
         *
         * @param chunk incoming chunk packet
         * @return assembled {@link MoleculeResources.Data} if transfer is complete, or {@code null} otherwise
         * @throws IOException if out-of-order transfers or size limits occur
         */
        MoleculeResources.Data accept(Chunk chunk) throws IOException {
            if (chunk.index == 0) {
                revision = chunk.revision;
                count = chunk.count;
                next = 0;
                buffer = new ByteArrayOutputStream();
            }
            if (buffer == null || revision != chunk.revision || count != chunk.count || next != chunk.index) {
                throw new IOException("Out-of-order molecule transfer");
            }
            if (buffer.size() + chunk.payload.length > MoleculeSync.MAX_BYTES) {
                throw new IOException("Molecule transfer exceeds limit");
            }
            buffer.write(chunk.payload);
            next++;
            if (next != count)
                return null;

            byte[] complete = buffer.toByteArray();
            reset();
            return MoleculeSync.decode(complete);
        }
    }
}

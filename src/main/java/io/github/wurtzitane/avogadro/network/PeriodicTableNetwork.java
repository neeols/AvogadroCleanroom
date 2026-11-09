package io.github.wurtzitane.avogadro.network;

import io.github.wurtzitane.avogadro.item.PeriodicTableItem;
import io.netty.buffer.ByteBuf;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

/** Handles network communication and packet serialization for periodic table item resizing actions. */
public final class PeriodicTableNetwork {

    /** Simple network wrapper channel instance for periodic table network messages. */
    private static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel("avogadro_table");

    private PeriodicTableNetwork() {}

    /** Registers network message handlers and packet channels on the server side. */
    public static void register() {
        CHANNEL.registerMessage(ResizeHandler.class, ResizeRequest.class, 0, Side.SERVER);
    }

    /**
     * Sends a resize request packet to the server for the held periodic table item.
     *
     * @param hand active player hand
     * @param selectedSlot selected hotbar slot index
     * @param direction resize direction (-1 or 1)
     */
    public static void requestResize(EnumHand hand, int selectedSlot, int direction) {
        if (hand != null && selectedSlot >= 0 && selectedSlot < 9 && (direction == -1 || direction == 1))
            CHANNEL.sendToServer(new ResizeRequest(hand, selectedSlot, direction));
    }

    /**
     * Resizes the held periodic table item based on player input, validating hands, hotbar slots, and sneaking state.
     *
     * @param player target player entity
     * @param hand active player hand
     * @param selectedSlot selected hotbar slot index
     * @param direction resize direction (-1 or 1)
     * @return {@code true} if resize was successful, {@code false} otherwise
     */
    public static boolean resizeHeld(EntityPlayerMP player, EnumHand hand, int selectedSlot, int direction) {
        if (player == null || hand == null || player.isDead || !player.isSneaking() || selectedSlot < 0
                || selectedSlot >= 9 || player.inventory.currentItem != selectedSlot
                || (direction != -1 && direction != 1))
            return false;
        ItemStack main = player.getHeldItemMainhand();
        if (hand == EnumHand.OFF_HAND && !main.isEmpty() && main.getItem() == PeriodicTableItem.INSTANCE)
            return false;
        ItemStack held = player.getHeldItem(hand);
        if (held.isEmpty() || held.getItem() != PeriodicTableItem.INSTANCE)
            return false;
        int oldWidth = PeriodicTableItem.width(held);
        int width = PeriodicTableItem.boundedWidth(oldWidth + direction);
        if (width != oldWidth) {
            PeriodicTableItem.setWidth(held, width);
            player.inventory.markDirty();
            player.inventoryContainer.detectAndSendChanges();
            if (player.openContainer != player.inventoryContainer)
                player.openContainer.detectAndSendChanges();
        }
        player.sendStatusMessage(new TextComponentTranslation("avogadro.periodic_table.size", width,
                PeriodicTableItem.heightForWidth(width)), true);
        return true;
    }

    /** Message packet requesting a resize action for the periodic table item. */
    public static final class ResizeRequest implements IMessage {

        /** Target player hand. */
        private EnumHand hand;

        /** Selected hotbar slot index. */
        private int selectedSlot;

        /** Resize direction value (-1 or 1). */
        private int direction;

        /** Flag indicating whether the request payload is valid. */
        private boolean valid;

        /** Default constructor required for network instantiation. */
        public ResizeRequest() {}

        /**
         * Constructs a resize request with specified hand, slot, and direction.
         *
         * @param hand target player hand
         * @param selectedSlot selected hotbar slot index
         * @param direction resize direction (-1 or 1)
         */
        public ResizeRequest(EnumHand hand, int selectedSlot, int direction) {
            this.hand = hand;
            this.selectedSlot = selectedSlot;
            this.direction = direction;
            valid = hand != null && selectedSlot >= 0 && selectedSlot < 9 && (direction == -1 || direction == 1);
        }

        /**
         * Serializes resize request data into a Netty byte buffer.
         *
         * @param buffer target byte buffer
         */
        @Override
        public void toBytes(ByteBuf buffer) {
            buffer.writeByte(hand == EnumHand.MAIN_HAND ? 0 : 1);
            buffer.writeByte(selectedSlot);
            buffer.writeByte(direction);
        }

        /**
         * Deserializes resize request data from a Netty byte buffer.
         *
         * @param buffer source byte buffer
         */
        @Override
        public void fromBytes(ByteBuf buffer) {
            valid = false;
            if (buffer.readableBytes() != 3)
                return;
            int encodedHand = buffer.readUnsignedByte();
            selectedSlot = buffer.readUnsignedByte();
            direction = buffer.readByte();
            hand = encodedHand == 0 ? EnumHand.MAIN_HAND : EnumHand.OFF_HAND;
            valid = encodedHand <= 1 && selectedSlot < 9 && (direction == -1 || direction == 1);
        }
    }

    /** Message handler for incoming resize request packets on the server side. */
    public static final class ResizeHandler implements IMessageHandler<ResizeRequest, IMessage> {

        /**
         * Handles incoming resize requests on the server thread.
         *
         * @param message incoming resize request message
         * @param context message context instance
         * @return response message, or {@code null}
         */
        @Override
        public IMessage onMessage(ResizeRequest message, MessageContext context) {
            if (!message.valid)
                return null;
            EntityPlayerMP player = context.getServerHandler().player;
            player.getServerWorld()
                    .addScheduledTask(() -> resizeHeld(player, message.hand, message.selectedSlot, message.direction));
            return null;
        }
    }
}

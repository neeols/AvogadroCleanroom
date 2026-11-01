package io.github.wurtzitane.avogadro.entity;

import io.github.wurtzitane.avogadro.Avogadro;
import io.github.wurtzitane.avogadro.item.PeriodicTableItem;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityHanging;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.registry.EntityRegistry;
import net.minecraftforge.fml.common.registry.IEntityAdditionalSpawnData;

import javax.annotation.Nullable;

/** Uses the native painting support, collision, damage and break rules. */
public final class PeriodicTableEntity extends EntityHanging implements IEntityAdditionalSpawnData {

    /** Width of the periodic table poster in blocks. */
    private int posterWidth = PeriodicTableItem.DEFAULT_WIDTH;

    /**
     * Constructs a new periodic table entity for client/server world instantiation.
     *
     * @param world world instance
     */
    public PeriodicTableEntity(World world) {
        super(world);
    }

    /**
     * Constructs a new periodic table entity with specified anchor position, facing direction, and width.
     *
     * @param world  world instance
     * @param anchor block anchor position
     * @param facing horizontal facing direction
     * @param width  poster width in blocks
     */
    public PeriodicTableEntity(World world, BlockPos anchor, EnumFacing facing, int width) {
        super(world, anchor);
        posterWidth = PeriodicTableItem.boundedWidth(width);
        updateFacingWithBoundingBox(facing);
    }

    /** Registers the periodic table entity with the Forge entity registry. */
    public static void registerEntity() {
        EntityRegistry.registerModEntity(Avogadro.rl("periodic_table"), PeriodicTableEntity.class,
                "periodic_table", 0, Loader.instance().activeModContainer().getMod(), 160, Integer.MAX_VALUE, false);
    }

    /**
     * Retrieves the poster width in blocks.
     *
     * @return width integer
     */
    public int widthBlocks() {
        return posterWidth;
    }

    /**
     * Retrieves the poster height in blocks.
     *
     * @return height integer
     */
    public int heightBlocks() {
        return PeriodicTableItem.heightForWidth(posterWidth);
    }

    /**
     * Retrieves the total width in pixels.
     *
     * @return width in pixels
     */
    @Override
    public int getWidthPixels() {
        return widthBlocks() * 16;
    }

    /**
     * Retrieves the total height in pixels.
     *
     * @return height in pixels
     */
    @Override
    public int getHeightPixels() {
        return heightBlocks() * 16;
    }

    /**
     * Writes entity dimension NBT data to compound.
     *
     * @param tag NBT tag compound
     */
    @Override
    public void writeEntityToNBT(NBTTagCompound tag) {
        tag.setInteger("PosterWidth", widthBlocks());
        tag.setInteger("PosterHeight", heightBlocks());
        super.writeEntityToNBT(tag);
    }

    /**
     * Reads entity dimension NBT data from compound.
     *
     * @param tag NBT tag compound
     */
    @Override
    public void readEntityFromNBT(NBTTagCompound tag) {
        posterWidth = tag.hasKey("PosterWidth", 99)
                ? PeriodicTableItem.boundedWidth(tag.getInteger("PosterWidth")) : PeriodicTableItem.DEFAULT_WIDTH;
        // Height always follows width, including when loading edited or older save data.
        super.readEntityFromNBT(tag);
    }

    /**
     * Writes additional spawn data to byte buffer for client synchronization.
     *
     * @param buffer byte buffer destination
     */
    @Override
    public void writeSpawnData(ByteBuf buffer) {
        buffer.writeLong(hangingPosition.toLong());
        buffer.writeByte(facingDirection.getHorizontalIndex());
        buffer.writeByte(widthBlocks());
        buffer.writeByte(heightBlocks());
    }

    /**
     * Reads additional spawn data from byte buffer on the client.
     *
     * @param buffer byte buffer source
     */
    @Override
    public void readSpawnData(ByteBuf buffer) {
        if (buffer.readableBytes() != Long.BYTES + 3) throw new IllegalArgumentException("Invalid periodic-table spawn data");
        hangingPosition = BlockPos.fromLong(buffer.readLong());
        EnumFacing facing = EnumFacing.byHorizontalIndex(buffer.readUnsignedByte());
        posterWidth = PeriodicTableItem.boundedWidth(buffer.readUnsignedByte());
        int height = buffer.readUnsignedByte();
        if (height != heightBlocks()) throw new IllegalArgumentException("Invalid periodic-table dimensions");
        updateFacingWithBoundingBox(facing);
    }

    /**
     * Handles initial player interaction for opening the periodic table viewer.
     *
     * @param player interacting player
     * @param hand   interaction hand
     * @return {@code true}
     */
    @Override
    public boolean processInitialInteract(EntityPlayer player, EnumHand hand) {
        if (world.isRemote) Avogadro.proxy.interactPeriodicTable(this, player);
        return true;
    }

    /**
     * Handles entity breaking and item dropping.
     *
     * @param breaker breaking entity, or {@code null}
     */
    @Override
    public void onBroken(@Nullable Entity breaker) {
        if (!world.getGameRules().getBoolean("doEntityDrops")) return;
        playSound(SoundEvents.ENTITY_PAINTING_BREAK, 1f, 1f);
        if (breaker instanceof EntityPlayer player && player.capabilities.isCreativeMode) return;
        entityDropItem(PeriodicTableItem.stack(widthBlocks()), 0);
    }

    /** Plays the placement sound effect. */
    @Override
    public void playPlaceSound() {
        playSound(SoundEvents.ENTITY_PAINTING_PLACE, 1f, 1f);
    }

    /**
     * Retrieves the picked item stack result for middle-click targeting.
     *
     * @param target ray trace result
     * @return item stack instance
     */
    @Override
    public ItemStack getPickedResult(RayTraceResult target) {
        return PeriodicTableItem.stack(widthBlocks());
    }

    /**
     * Sets position and angles.
     *
     * @param x     x coordinate
     * @param y     y coordinate
     * @param z     z coordinate
     * @param yaw   yaw angle
     * @param pitch pitch angle
     */
    @Override
    public void setLocationAndAngles(double x, double y, double z, float yaw, float pitch) {
        setPosition(x, y, z);
    }

    /**
     * Sets position and rotation directly from server updates.
     *
     * @param x          x coordinate
     * @param y          y coordinate
     * @param z          z coordinate
     * @param yaw        yaw angle
     * @param pitch      pitch angle
     * @param increments interpolation increments
     * @param teleport   whether to teleport
     */
    @Override
    public void setPositionAndRotationDirect(double x, double y, double z, float yaw, float pitch,
                                             int increments, boolean teleport) {
        if (hangingPosition != null) {
            BlockPos anchor = hangingPosition.add(x - posX, y - posY, z - posZ);
            setPosition(anchor.getX(), anchor.getY(), anchor.getZ());
        } else setPosition(x, y, z);
    }
}
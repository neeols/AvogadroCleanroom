package io.github.wurtzitane.avogadro.item;

import io.github.wurtzitane.avogadro.Avogadro;
import io.github.wurtzitane.avogadro.entity.PeriodicTableEntity;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/** A reusable reference table that can also be hung on a wall. */
@Mod.EventBusSubscriber(modid = Avogadro.MODID)
public final class PeriodicTableItem extends Item {

    /** Minimum allowable width in blocks for the periodic table poster. */
    public static final int MIN_WIDTH = 3;

    /** Maximum allowable width in blocks for the periodic table poster. */
    public static final int MAX_WIDTH = 12;

    /** Default width in blocks for the periodic table poster. */
    public static final int DEFAULT_WIDTH = 4;

    /** NBT tag key for storing the poster width value on item stacks. */
    public static final String WIDTH_TAG = "avogadro_poster_width";

    /** Singleton instance of the periodic table item. */
    public static final PeriodicTableItem INSTANCE = new PeriodicTableItem();

    /** Private constructor to initialize registry settings and stack size limits. */
    private PeriodicTableItem() {
        setRegistryName(Avogadro.MODID, "periodic_table");
        setTranslationKey("avogadro.periodic_table");
        setMaxStackSize(1);
    }

    /**
     * Registers the periodic table item with the Forge item registry.
     *
     * @param event registry event for items
     */
    @SubscribeEvent
    public static void register(RegistryEvent.Register<Item> event) {
        INSTANCE.setCreativeTab(MoleculeItems.ELEMENTS_TAB == null ? MoleculeItems.TAB : MoleculeItems.ELEMENTS_TAB);
        event.getRegistry().register(INSTANCE);
    }

    /**
     * Constrains a given width value within the permitted minimum and maximum bounds.
     *
     * @param width input width value
     * @return bounded width integer
     */
    public static int boundedWidth(int width) {
        return Math.clamp(width, MIN_WIDTH, MAX_WIDTH);
    }

    /**
     * Calculates the height in blocks corresponding to a given poster width.
     *
     * @param width poster width in blocks
     * @return calculated height integer
     */
    public static int heightForWidth(int width) {
        return Math.round(boundedWidth(width) * 2f / 3f);
    }

    /**
     * Retrieves the poster width from an item stack.
     *
     * @param stack item stack instance
     * @return width integer value
     */
    public static int width(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.hasTagCompound()
                || !stack.getTagCompound().hasKey(WIDTH_TAG, 99)) return DEFAULT_WIDTH;
        return boundedWidth(stack.getTagCompound().getInteger(WIDTH_TAG));
    }

    /**
     * Retrieves the poster height in blocks from an item stack.
     *
     * @param stack item stack instance
     * @return height integer value
     */
    public static int height(ItemStack stack) {
        return heightForWidth(width(stack));
    }

    /**
     * Sets the poster width on an item stack.
     *
     * @param stack item stack instance
     * @param width width integer value
     */
    public static void setWidth(ItemStack stack, int width) {
        if (stack == null || stack.isEmpty() || stack.getItem() != INSTANCE) return;
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null) {
            tag = new NBTTagCompound();
            stack.setTagCompound(tag);
        }
        tag.setInteger(WIDTH_TAG, boundedWidth(width));
    }

    /**
     * Creates a default periodic table item stack.
     *
     * @return constructed item stack instance
     */
    public static ItemStack stack() {
        return stack(DEFAULT_WIDTH);
    }

    /**
     * Creates a periodic table item stack with a specified width.
     *
     * @param width poster width in blocks
     * @return constructed item stack instance
     */
    public static ItemStack stack(int width) {
        ItemStack result = new ItemStack(INSTANCE);
        setWidth(result, width);
        return result;
    }

    /**
     * Populates sub-items for creative inventory tabs.
     *
     * @param tab   creative tab
     * @param items non-null item stack list
     */
    @Override
    public void getSubItems(CreativeTabs tab, NonNullList<ItemStack> items) {
        CreativeTabs target = MoleculeItems.ELEMENTS_TAB == null ? MoleculeItems.TAB : MoleculeItems.ELEMENTS_TAB;
        if (tab == CreativeTabs.SEARCH || tab == target) items.add(stack());
    }

    /**
     * Retrieves all creative tabs associated with this item.
     *
     * @return array of creative tabs
     */
    @Override
    public CreativeTabs[] getCreativeTabs() {
        return new CreativeTabs[]{MoleculeItems.ELEMENTS_TAB == null ? MoleculeItems.TAB : MoleculeItems.ELEMENTS_TAB};
    }

    /**
     * Handles initial item use interactions prior to block interaction, supporting sneak-clicks.
     *
     * @param player interacting player
     * @param world  world instance
     * @param pos    block position
     * @param side   facing side
     * @param hitX   hit X coordinate
     * @param hitY   hit Y coordinate
     * @param hitZ   hit Z coordinate
     * @param hand   interaction hand
     * @return enum action result
     */
    @Override
    public EnumActionResult onItemUseFirst(EntityPlayer player, World world, BlockPos pos, EnumFacing side,
                                           float hitX, float hitY, float hitZ, EnumHand hand) {
        // Run before the target block so sneaking also works on chests and other interactive blocks.
        if (!player.isSneaking()) return EnumActionResult.PASS;
        if (world.isRemote) Avogadro.proxy.openPeriodicTable();
        return EnumActionResult.SUCCESS;
    }

    /**
     * Handles right-click air interaction for opening the reference table interface when sneaking.
     *
     * @param world  world instance
     * @param player interacting player
     * @param hand   interaction hand
     * @return action result with item stack
     */
    @Override
    public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, EnumHand hand) {
        if (player.isSneaking()) {
            if (world.isRemote) Avogadro.proxy.openPeriodicTable();
            return new ActionResult<>(EnumActionResult.SUCCESS, player.getHeldItem(hand));
        }
        return new ActionResult<>(EnumActionResult.PASS, player.getHeldItem(hand));
    }

    /**
     * Handles block use interactions for placing periodic table posters or opening the viewer when sneaking.
     *
     * @param player  interacting player
     * @param world   world instance
     * @param pos     block position
     * @param hand    interaction hand
     * @param facing  facing direction
     * @param hitX    hit X coordinate
     * @param hitY    hit Y coordinate
     * @param hitZ    hit Z coordinate
     * @return enum action result
     */
    @Override
    public EnumActionResult onItemUse(EntityPlayer player, World world, BlockPos pos, EnumHand hand,
                                      EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (player.isSneaking()) {
            if (world.isRemote) Avogadro.proxy.openPeriodicTable();
            return EnumActionResult.SUCCESS;
        }
        ItemStack held = player.getHeldItem(hand);
        BlockPos anchor = pos.offset(facing);
        if (!facing.getAxis().isHorizontal() || !player.canPlayerEdit(anchor, facing, held))
            return EnumActionResult.FAIL;
        PeriodicTableEntity poster = new PeriodicTableEntity(world, anchor, facing, width(held));
        if (!poster.onValidSurface()) return EnumActionResult.FAIL;
        if (!world.isRemote) {
            if (!world.spawnEntity(poster)) return EnumActionResult.FAIL;
            poster.playPlaceSound();
            if (!player.capabilities.isCreativeMode) held.shrink(1);
        }
        return EnumActionResult.SUCCESS;
    }
}
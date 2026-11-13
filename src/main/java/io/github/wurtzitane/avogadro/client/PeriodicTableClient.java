package io.github.wurtzitane.avogadro.client;

import io.github.wurtzitane.avogadro.entity.PeriodicTableEntity;
import io.github.wurtzitane.avogadro.item.PeriodicTableItem;
import io.github.wurtzitane.avogadro.network.PeriodicTableNetwork;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderSpecificHandEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.fml.client.registry.RenderingRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Manages client-side event handlers, registrations, model mappings, and input interactions for periodic table items.
 */
public final class PeriodicTableClient {

    /** Flag indicating whether client handlers and listeners have already been initialized. */
    private static boolean initialized;

    private PeriodicTableClient() {}

    /**
     * Initializes client-side entity rendering handlers, event bus listeners, and resource reload listeners.
     */
    public static void init() {
        if (initialized)
            return;
        initialized = true;
        RenderingRegistry.registerEntityRenderingHandler(PeriodicTableEntity.class, PeriodicTableEntityRenderer::new);
        MinecraftForge.EVENT_BUS.register(PeriodicTableClient.class);
        ((IReloadableResourceManager) Minecraft.getMinecraft().getResourceManager())
                .registerReloadListener(manager -> PeriodicTableArtwork.clear());
    }

    /**
     * Registers model resource locations and tile entity item stack renderers for periodic table items.
     *
     * @param event model registry event
     */
    @SubscribeEvent
    public static void models(ModelRegistryEvent event) {
        ModelLoader.setCustomModelResourceLocation(PeriodicTableItem.INSTANCE, 0,
                new ModelResourceLocation("avogadro:periodic_table", "inventory"));
        PeriodicTableItem.INSTANCE.setTileEntityItemStackRenderer(new PeriodicTableItemRenderer());
    }

    @SubscribeEvent
    public static void baked(net.minecraftforge.client.event.ModelBakeEvent event) {
        var key = new ModelResourceLocation("avogadro:periodic_table", "inventory");
        var model = event.getModelRegistry().getObject(key);
        if (model != null && !(model instanceof PeriodicTableItemModel))
            event.getModelRegistry().putObject(key, new PeriodicTableItemModel(model));
    }

    /**
     * Handles mouse scroll events for resizing held periodic table items while sneaking.
     *
     * @param event mouse event
     */
    @SubscribeEvent
    public static void scroll(MouseEvent event) {
        var client = Minecraft.getMinecraft();
        if (event.getDwheel() == 0 || client.currentScreen != null || client.player == null
                || !client.player.isSneaking())
            return;
        EnumHand hand = isTable(client.player.getHeldItemMainhand())
                ? EnumHand.MAIN_HAND
                : isTable(client.player.getHeldItemOffhand()) ? EnumHand.OFF_HAND : null;
        if (hand == null)
            return;
        event.setCanceled(true);
        PeriodicTableNetwork.requestResize(hand, client.player.inventory.currentItem,
                Integer.signum(event.getDwheel()));
    }

    /**
     * Cancels hand rendering when the Avogadro menu is open and a periodic table item is held.
     *
     * @param event render specific hand event
     */
    @SubscribeEvent
    public static void hand(RenderSpecificHandEvent event) {
        if (isTable(event.getItemStack()) && PeriodicTableScreen.isAvogadroMenuOpen())
            event.setCanceled(true);
    }

    /**
     * Cancels hotbar overlay rendering when the Avogadro menu is open.
     *
     * @param event render game overlay pre event
     */
    @SubscribeEvent
    public static void overlay(RenderGameOverlayEvent.Pre event) {
        if (event.getType() == RenderGameOverlayEvent.ElementType.HOTBAR && PeriodicTableScreen.isAvogadroMenuOpen())
            event.setCanceled(true);
    }

    /**
     * Adds informational tooltips to periodic table item stacks.
     *
     * @param event item tooltip event
     */
    @SubscribeEvent
    public static void tooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (!isTable(stack))
            return;
        var lines = event.getToolTip();
        lines.add(TextFormatting.DARK_AQUA + I18n.format("avogadro.periodic_table.size", PeriodicTableItem.width(stack),
                PeriodicTableItem.height(stack)));
        for (String key : new String[]{"open", "place", "resize", "painting"})
            lines.add(TextFormatting.GRAY + I18n.format("avogadro.periodic_table." + key));
    }

    /**
     * Helper method checking whether an item stack is an instance of the periodic table item.
     *
     * @param stack item stack to check
     * @return {@code true} if valid table item stack, {@code false} otherwise
     */
    private static boolean isTable(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() == PeriodicTableItem.INSTANCE;
    }
}

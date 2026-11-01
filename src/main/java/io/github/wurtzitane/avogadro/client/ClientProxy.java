package io.github.wurtzitane.avogadro.client;

import io.github.wurtzitane.avogadro.Avogadro;
import io.github.wurtzitane.avogadro.CommonProxy;
import io.github.wurtzitane.avogadro.Config;
import io.github.wurtzitane.avogadro.compat.CompatibilityHooks;
import io.github.wurtzitane.avogadro.client.export.ExportCommand;
import io.github.wurtzitane.avogadro.client.export.MoleculeMediaExport;
import io.github.wurtzitane.avogadro.molecule.MoleculeNetwork;
import io.github.wurtzitane.avogadro.molecule.MoleculeResources;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.event.world.WorldEvent;

/**
 * Client-side proxy handling client-only initialization, event subscriptions, and lifecycle hooks.
 * <p>Instantiated only by the CLIENT side of {@code @SidedProxy}.
 */
public final class ClientProxy extends CommonProxy {

    /** Queue of callbacks to run after the current screen closes. */
    private static final java.util.ArrayDeque<Runnable> SCREEN_RETURNS = new java.util.ArrayDeque<>();

    /**
     * Registers a callback action to execute after a screen is closed.
     *
     * @param action runnable action
     */
    static void afterScreenClosed(Runnable action) {
        SCREEN_RETURNS.addLast(action);
    }

    /** Client tick event handler processing queued screen return callbacks. */
    @SubscribeEvent
    public void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START || SCREEN_RETURNS.isEmpty()) {
            return;
        }
        // A close callback runs before ModularUI finishes disposing its old screen.
        var actions = new java.util.ArrayList<>(SCREEN_RETURNS);
        SCREEN_RETURNS.clear();
        actions.forEach(Runnable::run);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void openPeriodicTable() {
        PeriodicTableScreen.open();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void interactPeriodicTable(io.github.wurtzitane.avogadro.entity.PeriodicTableEntity entity,
                                      net.minecraft.entity.player.EntityPlayer player) {
        var client = Minecraft.getMinecraft();
        if (player != client.player || client.currentScreen != null) {
            return;
        }
        if (player.isSneaking()) {
            PeriodicTableScreen.open();
        } else {
            int element = PeriodicTableTarget.element(entity, client.getRenderPartialTicks());
            if (element >= 0) {
                MoleculeExaminer.openElement(
                        io.github.wurtzitane.avogadro.api.ElementCatalog.byAtomicNumber(element).symbol(), null);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public io.github.wurtzitane.avogadro.api.MoleculeView moleculeItemView() {
        // Integrated-server logic must never resolve client-only resource overrides.
        if (net.minecraftforge.fml.common.FMLCommonHandler.instance().getEffectiveSide().isServer()) {
            return super.moleculeItemView();
        }
        return io.github.wurtzitane.avogadro.molecule.MoleculeStore.view();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String moleculeItemName(io.github.wurtzitane.avogadro.molecule.MoleculeDef definition) {
        return io.github.wurtzitane.avogadro.item.MoleculeItems.displayName(definition);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void preInit() {
        AvogadroKeys.register();
        PeriodicTableClient.init();
        CompatibilityHooks.initialize();
        MinecraftForge.EVENT_BUS.register(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void init() {
        // Native monitor discovery can initialize display services. Start it during
        // loading, so even driver-side first-use work is finished before a viewer opens.
        ViewerFramerate.prewarm();
        ViewerInitialization.prepare();
        ClientCommandHandler.instance.registerCommand(new ExportCommand());
        MinecraftForge.EVENT_BUS.register(new MoleculeMediaExport());
        ((IReloadableResourceManager) Minecraft.getMinecraft().getResourceManager())
                .registerReloadListener(manager -> MoleculeMediaExport.cancel());
        ((IReloadableResourceManager) Minecraft.getMinecraft().getResourceManager())
                .registerReloadListener(manager -> MoleculeRenderer.clearCaches());
        ((IReloadableResourceManager) Minecraft.getMinecraft().getResourceManager())
                .registerReloadListener(manager -> PixelFont.release());
        ((IReloadableResourceManager) Minecraft.getMinecraft().getResourceManager())
                .registerReloadListener(ElementPalette.INSTANCE);
        // Registration immediately invokes the listener for the already-loaded packs.
        ((IReloadableResourceManager) Minecraft.getMinecraft().getResourceManager())
                .registerReloadListener(new MoleculeAssets());
        ((IReloadableResourceManager) Minecraft.getMinecraft().getResourceManager())
                .registerReloadListener(MoleculeCategoryStyle.INSTANCE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void applyServerData(MoleculeResources.Data data) {
        Minecraft.getMinecraft().addScheduledTask(() -> MoleculeClientStore.applyServerData(data));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void disconnect() {
        SCREEN_RETURNS.clear();
        MoleculeMediaExport.cancel();
        MoleculeNetwork.resetClient();
        Minecraft.getMinecraft().addScheduledTask(() -> {
            CrystalPreviewCache.clear();
            MoleculeRenderer.clearCaches();
            PixelFont.release();
            MoleculeCamera.resetAll();
            MoleculeClientStore.disconnect();
        });
    }

    /** Handles client disconnection events from the server. */
    @SubscribeEvent
    public void disconnected(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        disconnect();
    }

    /** Handles world unload events to clear client-side caches. */
    @SubscribeEvent
    public void worldUnloaded(WorldEvent.Unload event) {
        if (event.getWorld().isRemote) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                CrystalPreviewCache.clear();
                MoleculeRenderer.clearCaches();
                PixelFont.release();
            });
        }
    }

    /** Handles GUI open events to reset active rotations and cameras. */
    @SubscribeEvent
    public void screenChanged(GuiOpenEvent event) {
        MoleculeRotation.cancelActiveDrag();
        MoleculeCamera.resetAll();
    }

    /** Manages render tick frame budgets and caching resets. */
    @SubscribeEvent(priority = net.minecraftforge.fml.common.eventhandler.EventPriority.LOWEST)
    public void renderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            FrameWorkBudget.beginFrame(ViewerFramerate.isActive() ? ViewerFramerate.limit() :
                    Minecraft.getMinecraft().getLimitFramerate());
            MoleculeClientStore.pump();
            MoleculeItemRenderer.beginFrame();
            MoleculeAnimationCache.beginFrame();
            MoleculeRotation.beginFrame();
            MoleculeCamera.beginFrame();
        } else {
            MoleculeRotation.endFrame();
            MoleculeCamera.endFrame();
            try {
                MoleculeAnimationCache.endFrame();
                FrameWorkBudget.beginExport();
                try {
                    io.github.wurtzitane.avogadro.client.export.MoleculeMediaExport.advance();
                } finally {
                    FrameWorkBudget.endExport();
                }
            } finally {
                FrameWorkBudget.endFrame();
            }
        }
    }

    /** Handles configuration change events and synchronizes rendering modes. */
    @SubscribeEvent
    public void configChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
        if (Avogadro.MODID.equals(event.getModID())) {
            Config.RenderingMode previousMode = Config.RENDERING_MODE.get();
            Config.sync();
            // Presentation settings are either read per draw or already part of a cache key.
            if (previousMode != Config.RENDERING_MODE.get()) {
                MoleculeCamera.resetAll();
            }
        }
    }
}
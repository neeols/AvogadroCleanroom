package io.github.wurtzitane.avogadro.compat.waila;

import java.awt.Dimension;
import java.util.List;

import io.github.wurtzitane.avogadro.Config;
import io.github.wurtzitane.avogadro.client.MoleculeTooltips;
import io.github.wurtzitane.avogadro.client.WorldHudController;
import io.github.wurtzitane.avogadro.molecule.MoleculeDef;
import io.github.wurtzitane.avogadro.molecule.MoleculeStore;

import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.event.FMLInterModComms;

import mcp.mobius.waila.api.IWailaCommonAccessor;
import mcp.mobius.waila.api.IWailaConfigHandler;
import mcp.mobius.waila.api.IWailaDataAccessor;
import mcp.mobius.waila.api.IWailaDataProvider;
import mcp.mobius.waila.api.IWailaRegistrar;
import mcp.mobius.waila.api.IWailaTooltipRenderer;
import mcp.mobius.waila.api.SpecialChars;

/** The legacy WAILA API is provided on 1.12.2 by HWYLA. */
public final class WailaCompat implements IWailaDataProvider, IWailaTooltipRenderer {

    /** Renderer registration string for WAILA custom molecule tooltips. */
    private static final String RENDERER = "avogadro:molecule";

    /** Flag indicating whether registration has already occurred. */
    private static boolean registered;

    /** Initializes WAILA compatibility via Inter-Mod Communications. */
    public static void initialize() {
        FMLInterModComms.sendMessage("waila", "register", WailaCompat.class.getName() + ".register");
    }

    /**
     * Registers WAILA data providers, tail providers, and tooltip renderers.
     *
     * @param registrar WAILA registrar instance
     */
    public static void register(IWailaRegistrar registrar) {
        if (registered)
            return;
        registered = true;
        WailaCompat provider = new WailaCompat();
        // A separate tail group follows both core Block and fluid footer groups.
        registrar.registerTailProvider(provider, Object.class);
        registrar.registerTooltipRenderer(RENDERER, provider);
        registrar.registerBodyProvider(new mcp.mobius.waila.api.IWailaEntityProvider() {
            @Override
            public List<String> getWailaBody(net.minecraft.entity.Entity entity, List<String> tooltip,
                    mcp.mobius.waila.api.IWailaEntityAccessor accessor, IWailaConfigHandler config) {
                if (accessor.getWorld().isRemote && WorldHudController.active(Config.HudProvider.WAILA)
                        && entity instanceof io.github.wurtzitane.avogadro.entity.PeriodicTableEntity table) {
                    String label = io.github.wurtzitane.avogadro.client.PeriodicTableTarget.label(table);
                    if (label != null)
                        tooltip.add(label);
                }
                return tooltip;
            }
        }, io.github.wurtzitane.avogadro.entity.PeriodicTableEntity.class);
    }

    /**
     * Appends custom molecule render strings to the WAILA tail tooltip.
     *
     * @param stack item stack instance
     * @param tooltip tooltip string list
     * @param accessor WAILA data accessor
     * @param config WAILA config handler
     * @return updated tooltip string list
     */
    @Override
    public List<String> getWailaTail(ItemStack stack, List<String> tooltip, IWailaDataAccessor accessor,
            IWailaConfigHandler config) {
        // TheOneSmeagle also invokes WAILA providers on the server and converts their
        // strings to TOP text. Never let a client renderer marker enter that bridge.
        if (!accessor.getWorld().isRemote || !WorldHudController.active(Config.HudProvider.WAILA))
            return tooltip;
        MoleculeDef definition = WorldHudController.match(accessor.getWorld(), accessor.getPosition(),
                accessor.getMOP(), stack);
        if (definition != null)
            tooltip.add(SpecialChars.getRenderString(RENDERER, definition.id().toString()));
        return tooltip;
    }

    /**
     * Returns the dimension requirements for custom tooltip rendering.
     *
     * @param parameters renderer parameter strings
     * @param accessor common accessor instance
     * @return dimension size bounds
     */
    @Override
    public Dimension getSize(String[] parameters, IWailaCommonAccessor accessor) {
        return definition(parameters, accessor) == null
                ? new Dimension(0, 0)
                : new Dimension(Config.TOOLTIP_SIZE.get() + 24, Config.TOOLTIP_SIZE.get() + 12);
    }

    /**
     * Renders the custom molecule tooltip element inside WAILA.
     *
     * @param parameters renderer parameter strings
     * @param accessor common accessor instance
     */
    @Override
    public void draw(String[] parameters, IWailaCommonAccessor accessor) {
        MoleculeDef definition = definition(parameters, accessor);
        if (definition != null)
            MoleculeTooltips.draw(definition, 12, 6, Config.TOOLTIP_SIZE.get());
    }

    /**
     * Resolves a molecule definition from WAILA parameters and client context.
     *
     * @param parameters renderer parameter strings
     * @param accessor common accessor instance
     * @return resolved {@link MoleculeDef}, or {@code null}
     */
    private static MoleculeDef definition(String[] parameters, IWailaCommonAccessor accessor) {
        if (!accessor.getWorld().isRemote || !WorldHudController.active(Config.HudProvider.WAILA)
                || parameters.length != 1)
            return null;
        try {
            return MoleculeStore.get(new ResourceLocation(parameters[0]));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}

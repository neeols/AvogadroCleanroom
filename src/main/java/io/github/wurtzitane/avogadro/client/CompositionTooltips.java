package io.github.wurtzitane.avogadro.client;

import io.github.wurtzitane.avogadro.Avogadro;
import io.github.wurtzitane.avogadro.Config;
import io.github.wurtzitane.avogadro.api.AvogadroApi;
import io.github.wurtzitane.avogadro.api.MoleculeClassification;
import io.github.wurtzitane.avogadro.item.MoleculeItems;
import io.github.wurtzitane.avogadro.molecule.MoleculeStore;

import net.minecraft.client.resources.I18n;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

/**
 * Event subscriber for adding composition tooltips to registered items.
 * <p>
 * Presentation notes apply equally to any host's associated material items.
 */
@Mod.EventBusSubscriber(modid = Avogadro.MODID, value = Side.CLIENT)
public final class CompositionTooltips {

    private CompositionTooltips() {}

    /**
     * Appends variable composition tooltips to item stacks if applicable.
     *
     * @param event the item tooltip event
     */
    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        var stack = event.getItemStack();
        if (!Config.ENABLE_TOOLTIPS.get() || MoleculeItems.id(stack) != null
                || AvogadroApi.isItemTooltipExcluded(stack)) {
            return;
        }
        var id = MoleculeStore.lookup(stack);
        var definition = id == null ? null : MoleculeStore.get(id);
        if (definition == null) {
            return;
        }
        if (MoleculeClassification.of(definition) == MoleculeClassification.MIXTURE) {
            event.getToolTip().add(I18n.format("avogadro.item.variable_composition"));
        }
    }
}

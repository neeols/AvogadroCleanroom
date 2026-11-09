package io.github.wurtzitane.avogadro;

import io.github.wurtzitane.avogadro.molecule.MoleculeResources;

/** No client types may be referenced from this dedicated-server entry point. */
public class CommonProxy {

    /**
     * Returns the appropriate molecule view depending on whether a dedicated server or integrated client context is
     * active.
     *
     * @return constructed {@link io.github.wurtzitane.avogadro.api.MoleculeView}
     */
    public io.github.wurtzitane.avogadro.api.MoleculeView moleculeItemView() {
        return net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance() == null
                ? io.github.wurtzitane.avogadro.api.AvogadroApi.registeredView()
                : io.github.wurtzitane.avogadro.api.AvogadroApi.serverView();
    }

    /**
     * Returns the fallback item name string for a given molecule definition.
     *
     * @param definition molecule definition instance
     * @return fallback item name string
     */
    public String moleculeItemName(io.github.wurtzitane.avogadro.molecule.MoleculeDef definition) {
        return io.github.wurtzitane.avogadro.item.MoleculeItems.fallbackName(definition);
    }

    /** Performs proxy pre-initialization tasks. */
    public void preInit() {}

    /** Performs proxy initialization tasks. */
    public void init() {}

    /** Opens the periodic table UI interface. */
    public void openPeriodicTable() {}

    /**
     * Handles player interactions with a periodic table entity.
     *
     * @param entity periodic table entity instance
     * @param player player interacting with the entity
     */
    public void interactPeriodicTable(io.github.wurtzitane.avogadro.entity.PeriodicTableEntity entity,
            net.minecraft.entity.player.EntityPlayer player) {}

    /**
     * Applies server-side resource data updates.
     *
     * @param data molecule resource data instance
     */
    public void applyServerData(MoleculeResources.Data data) {}

    /** Handles proxy disconnection events. */
    public void disconnect() {}
}

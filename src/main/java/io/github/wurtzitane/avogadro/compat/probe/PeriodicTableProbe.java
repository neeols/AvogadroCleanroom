package io.github.wurtzitane.avogadro.compat.probe;

import java.util.function.Function;

import io.github.wurtzitane.avogadro.entity.PeriodicTableEntity;
import io.github.wurtzitane.avogadro.item.PeriodicTableItem;

import mcjty.theoneprobe.Tools;
import mcjty.theoneprobe.api.*;
import mcjty.theoneprobe.config.Config;

/** TOP's display override keeps the painting inside a normal item icon slot. */
public final class PeriodicTableProbe implements Function<ITheOneProbe, Void> {

    /**
     * Applies and registers the periodic table entity display override with The One Probe.
     *
     * @param probe TheOneProbe API instance
     * @return {@code null}
     */
    @Override
    public Void apply(ITheOneProbe probe) {
        probe.registerEntityDisplayOverride((mode, info, player, world, entity, hit) -> {
            if (!(entity instanceof PeriodicTableEntity table))
                return false;
            boolean showMod = Tools.show(mode, Config.getRealConfig().getShowModName());
            IProbeInfo row = info.horizontal(info.defaultLayoutStyle().alignment(ElementAlignment.ALIGN_CENTER));
            row.item(PeriodicTableItem.stack(table.widthBlocks()));
            IProbeInfo text = showMod ? row.vertical() : row;
            String name = entity.hasCustomName()
                    ? entity.getCustomNameTag()
                    : IProbeInfo.STARTLOC + "entity.periodic_table.name" + IProbeInfo.ENDLOC;
            text.text(TextStyleClass.NAME + name);
            if (showMod)
                text.text(TextStyleClass.MODNAME + Tools.getModName(entity));
            return true;
        });
        return null;
    }
}

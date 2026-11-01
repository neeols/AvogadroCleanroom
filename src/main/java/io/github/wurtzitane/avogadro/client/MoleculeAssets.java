package io.github.wurtzitane.avogadro.client;

import io.github.wurtzitane.avogadro.Avogadro;
import io.github.wurtzitane.avogadro.molecule.MoleculeResources;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.AbstractResourcePack;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import net.minecraft.client.resources.IResourcePack;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;

import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Handles narrow file-based resource discovery for molecules and binding dictionaries.
 * <p>Narrow file-based resource discovery: molecules and their binding dictionaries only.
 */
public final class MoleculeAssets implements IResourceManagerReloadListener {

    /**
     * {@inheritDoc}
     */
    @Override
    public void onResourceManagerReload(IResourceManager manager) {
        Set<Path> sources = new LinkedHashSet<>();
        Set<IResourcePack> selected = new LinkedHashSet<>();
        var repository = Minecraft.getMinecraft().getResourcePackRepository();
        repository.getRepositoryEntries().forEach(entry -> selected.add(entry.getResourcePack()));
        IResourcePack serverPack = repository.getServerResourcePack();
        if (serverPack != null) {
            selected.add(serverPack);
        }
        for (IResourcePack pack : selected) {
            if (pack instanceof AbstractResourcePack filePack) {
                try {
                    File source = ObfuscationReflectionHelper.getPrivateValue(AbstractResourcePack.class, filePack,
                            "resourcePackFile", "field_110597_b");
                    sources.add(source.toPath());
                } catch (RuntimeException e) {
                    Avogadro.LOGGER.warn("Cannot enumerate molecule resources in pack {}", pack.getPackName(), e);
                }
            } else {
                Avogadro.LOGGER.warn("Molecule resource discovery requires a directory or ZIP pack; cannot enumerate {} ({})",
                        pack.getPackName(), pack.getClass().getName());
            }
        }
        Path config = Avogadro.configDirectory;
        // Capture selected pack paths here; workers never touch the live pack repository.
        MoleculeClientStore.reloadLocal(() -> {
            var assets = new MoleculeResources.Accumulator(MoleculeResources.readMods("assets"));
            for (Path source : sources) {
                GeometryPreparation.checkCancelled();
                assets.add(MoleculeResources.readSource(source, "assets"));
            }
            GeometryPreparation.checkCancelled();
            assets.add(MoleculeResources.readSource(config.resolve("resources"), "assets"));
            return new MoleculeClientStore.LocalLayers(MoleculeResources.readCommonDefaults(), assets.toData(),
                    MoleculeResources.readLocal(config.resolve("molecules")));
        });
    }
}
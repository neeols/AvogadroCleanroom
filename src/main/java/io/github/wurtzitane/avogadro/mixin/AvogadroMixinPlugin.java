package io.github.wurtzitane.avogadro.mixin;

import java.util.List;
import java.util.Set;

import com.cleanroommc.discovery.CleanroomModDiscoverer;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Decide optional targets during discovery without resolving their classes. */
public final class AvogadroMixinPlugin implements IMixinConfigPlugin {

    /**
     * Called during mixin configuration plugin loading.
     *
     * @param mixinPackage mixin package string
     */
    @Override
    public void onLoad(String mixinPackage) {}

    /**
     * Returns the reference mapper configuration file name.
     *
     * @return reference mapper config string, or {@code null}
     */
    @Override
    public String getRefMapperConfig() {
        return null;
    }

    /**
     * Returns the list of mixin classes to apply dynamically.
     *
     * @return list of mixin class names, or {@code null}
     */
    @Override
    public List<String> getMixins() {
        return null;
    }

    /**
     * Accepts target class sets during mixin discovery.
     *
     * @param mine set of target classes owned by this plugin
     * @param others set of target classes owned by other plugins
     */
    @Override
    public void acceptTargets(Set<String> mine, Set<String> others) {}

    /**
     * Executed before a mixin is applied to a target class node.
     *
     * @param target target class name
     * @param node class node instance
     * @param mixin mixin class name
     * @param info mixin info instance
     */
    @Override
    public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}

    /**
     * Executed after a mixin has been applied to a target class node.
     *
     * @param target target class name
     * @param node class node instance
     * @param mixin mixin class name
     * @param info mixin info instance
     */
    @Override
    public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {}

    /**
     * Determines whether a specific mixin should be applied based on target namespaces and mod loading status.
     *
     * @param target target class name
     * @param mixin mixin class name
     * @return {@code true} if the mixin should be applied, {@code false} otherwise
     */
    @Override
    public boolean shouldApplyMixin(String target, String mixin) {
        CleanroomModDiscoverer mods = CleanroomModDiscoverer.instance();
        if (target.startsWith("mezz.jei."))
            return mods.isModPresent("jei");
        if (target.startsWith("codechicken.lib."))
            return mods.isModPresent("codechickenlib");
        if (target.startsWith("mcjty.theoneprobe."))
            return mods.isModPresent("theoneprobe");
        return true;
    }
}

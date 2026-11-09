package io.github.wurtzitane.avogadro.recipe;

import java.util.function.BooleanSupplier;

import com.google.gson.JsonObject;

import io.github.wurtzitane.avogadro.Config;

import net.minecraftforge.common.crafting.IConditionFactory;
import net.minecraftforge.common.crafting.JsonContext;

/**
 * Forge crafting condition factory controlling whether the periodic table crafting recipe is enabled based on
 * configuration settings. Disabling crafting does not remove saved items or hanging posters.
 */
public final class PeriodicTableRecipeCondition implements IConditionFactory {

    /**
     * Parses the condition factory context and JSON object, returning a boolean supplier that evaluates whether
     * periodic table crafting is enabled in the configuration.
     *
     * @param context JSON context instance
     * @param json JSON object defining condition parameters
     * @return a {@link BooleanSupplier} evaluating the periodic table crafting configuration state
     */
    @Override
    public BooleanSupplier parse(JsonContext context, JsonObject json) {
        return () -> Config.PERIODIC_TABLE_CRAFTING.get();
    }
}

package io.github.wurtzitane.avogadro.client;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * Manages element-related interaction actions, material form links, and navigation routines.
 */
public final class ElementActions {

    /** Predicate checking whether material forms are available for an element symbol. */
    private static Predicate<String> available = symbol -> false;

    /** BiConsumer action for opening material forms given an element symbol and return callback. */
    private static BiConsumer<String, Runnable> materialForms = (symbol, back) -> {};

    /** Flag tracking whether all windows are currently closing. */
    private static boolean closingAll;

    private ElementActions() {}

    /**
     * Registers material forms availability predicate and opening handler.
     *
     * @param available predicate testing element symbol availability
     * @param open consumer action to open material forms
     */
    public static void registerMaterialForms(Predicate<String> available, BiConsumer<String, Runnable> open) {
        ElementActions.available = Objects.requireNonNull(available);
        materialForms = Objects.requireNonNull(open);
    }

    /**
     * Checks whether material forms exist for the specified element symbol.
     *
     * @param symbol element symbol
     * @return {@code true} if available, {@code false} otherwise
     */
    static boolean hasMaterialForms(String symbol) {
        return symbol != null && available.test(symbol);
    }

    /** Opens material forms for the specified element symbol with a return callback. */
    static void openMaterialForms(String symbol, Runnable back) {
        if (hasMaterialForms(symbol)) {
            materialForms.accept(symbol, back);
        }
    }

    /** Begins navigation, resetting the closing-all state flag. */
    static void beginNavigation() {
        closingAll = false;
    }

    /** Checks whether returning navigation is currently allowed. */
    static boolean returningAllowed() {
        return !closingAll;
    }

    /** Closes all active viewer windows and screens. */
    public static void closeAll() {
        if (ViewerWindows.closeAll()) {
            return;
        }
        closingAll = true;
        var client = net.minecraft.client.Minecraft.getMinecraft();
        if (client.player != null) {
            client.player.closeScreen();
        }
        client.displayGuiScreen(null);
    }
}

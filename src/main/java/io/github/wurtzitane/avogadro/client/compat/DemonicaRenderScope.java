package io.github.wurtzitane.avogadro.client.compat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Balances Avogadro draws with Demonica’s optional shared-context hooks. With no compatible
 * GL state manager present the scope is inert; an installed but incompatible API fails
 * during linking instead of silently drawing with an unbalanced state cache.
 * Use try-with-resources on the rendering thread that opened the scope.
 */
public final class DemonicaRenderScope implements AutoCloseable {
    private static final MethodHandle BEGIN;
    private static final MethodHandle END;

    static {
        MethodHandle begin = null, end = null;
        ClassLoader loader = DemonicaRenderScope.class.getClassLoader();
        if (loader.getResource("com/gtnewhorizons/angelica/glsm/GLStateManager.class") != null) {
            try {
                Class<?> state = Class.forName("com.gtnewhorizons.angelica.glsm.GLStateManager", false, loader);
                var lookup = MethodHandles.publicLookup();
                var signature = MethodType.methodType(void.class);
                begin = lookup.findStatic(state, "beginForeignDraw", signature);
                end = lookup.findStatic(state, "endForeignDraw", signature);
            } catch (ReflectiveOperationException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }
        BEGIN = begin;
        END = end;
    }

    private final Thread owner = Thread.currentThread();
    private boolean closed;

    private DemonicaRenderScope() {
        begin();
    }

    /** Begins a scope owned by the calling thread. Closing it twice is harmless. */
    public static DemonicaRenderScope open() {
        return new DemonicaRenderScope();
    }

    /** Ends this scope on its opening thread. Cross-thread closure is rejected before changing its closed state. */
    @Override
    public void close() {
        if (closed)
            return;
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Render scope closed on another thread");
        closed = true;
        end();
    }

    /** Calls the optional begin hook; explicit callers must pair it with end in a finally block. */
    public static void begin() {
        invoke(BEGIN);
    }

    /** Calls the optional end hook. Prefer an AutoCloseable scope when manual pairing is unnecessary. */
    public static void end() {
        invoke(END);
    }

    private static void invoke(MethodHandle operation) {
        if (operation == null)
            return;
        try {
            operation.invokeExact();
        } catch (Throwable failure) {
            if (failure instanceof Error error)
                throw error;
            if (failure instanceof RuntimeException exception)
                throw exception;
            throw new IllegalStateException("Demonica render scope failed", failure);
        }
    }
}

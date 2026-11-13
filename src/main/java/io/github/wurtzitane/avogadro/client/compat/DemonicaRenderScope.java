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
    private static final Class<?> WORLD_PIPELINE;
    private static final MethodHandle MANAGER;
    private static final MethodHandle PIPELINE;
    private static final MethodHandle OVERRIDE;
    private static final MethodHandle INPUTS;
    private static final MethodHandle SET_INPUTS;
    private static final MethodHandle RESTORE;
    private static final MethodHandle SHADOW;

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
        Class<?> pipeline = null;
        MethodHandle manager = null, current = null, override = null, inputs = null, setInputs = null, restore = null,
                shadow = null;
        if (loader.getResource("net/coderbot/iris/pipeline/DeferredWorldRenderingPipeline.class") != null) {
            try {
                var lookup = MethodHandles.publicLookup();
                Class<?> iris = Class.forName("net.coderbot.iris.Iris", false, loader);
                var accessor = iris.getMethod("getPipelineManager");
                Class<?> owner = accessor.getReturnType();
                pipeline = Class.forName("net.coderbot.iris.pipeline.DeferredWorldRenderingPipeline", false, loader);
                Class<?> availability = Class.forName("net.coderbot.iris.gbuffer_overrides.matching.InputAvailability",
                        false, loader);
                manager = lookup.unreflect(accessor).asType(MethodType.methodType(Object.class));
                current = lookup.unreflect(owner.getMethod("getPipelineNullable"))
                        .asType(MethodType.methodType(Object.class, Object.class));
                override = lookup.findVirtual(pipeline, "shouldOverrideShaders", MethodType.methodType(boolean.class))
                        .asType(MethodType.methodType(boolean.class, Object.class));
                inputs = lookup
                        .findConstructor(availability, MethodType.methodType(void.class, boolean.class, boolean.class))
                        .asType(MethodType.methodType(Object.class, boolean.class, boolean.class));
                setInputs = lookup.findVirtual(pipeline, "setInputs", MethodType.methodType(void.class, availability))
                        .asType(MethodType.methodType(void.class, Object.class, Object.class));
                restore = lookup.findVirtual(pipeline, "restorePassAfterForeignDraw", MethodType.methodType(void.class))
                        .asType(MethodType.methodType(void.class, Object.class));
                Class<?> shadows = Class.forName("net.coderbot.iris.shadows.ShadowRenderingState", false, loader);
                shadow = lookup.findStatic(shadows, "areShadowsCurrentlyBeingRendered",
                        MethodType.methodType(boolean.class));
            } catch (ReflectiveOperationException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }
        WORLD_PIPELINE = pipeline;
        MANAGER = manager;
        PIPELINE = current;
        OVERRIDE = override;
        INPUTS = inputs;
        SET_INPUTS = setInputs;
        RESTORE = restore;
        SHADOW = shadow;
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

    public static void worldSurface(boolean textured, boolean lightmap) {
        if (MANAGER == null)
            return;
        try {
            Object manager = MANAGER.invokeExact();
            Object pipeline = PIPELINE.invokeExact(manager);
            if (!WORLD_PIPELINE.isInstance(pipeline)
                    || !(boolean) OVERRIDE.invokeExact(pipeline) && !(boolean) SHADOW.invokeExact())
                return;
            Object inputs = INPUTS.invokeExact(textured, lightmap);
            SET_INPUTS.invokeExact(pipeline, inputs);
            RESTORE.invokeExact(pipeline);
        } catch (Throwable failure) {
            throw propagate(failure);
        }
    }

    private static void invoke(MethodHandle operation) {
        if (operation == null)
            return;
        try {
            operation.invokeExact();
        } catch (Throwable failure) {
            throw propagate(failure);
        }
    }

    private static RuntimeException propagate(Throwable failure) {
        if (failure instanceof Error error)
            throw error;
        if (failure instanceof RuntimeException exception)
            return exception;
        return new IllegalStateException("Demonica render scope failed", failure);
    }
}

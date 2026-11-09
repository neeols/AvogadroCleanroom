package io.github.wurtzitane.avogadro.client;

import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import com.cleanroommc.lwjgly.LWJGLY;

import io.github.wurtzitane.avogadro.Config;

import org.lwjgl.opengl.Display;
import org.lwjgl.sdl.SDLVideo;

/** A temporary menu limit; Minecraft's frame limit and VSync settings stay intact. */
public final class ViewerFramerate {

    /** Time interval in nanoseconds between monitor refresh rate sample requests (1 second). */
    private static final long REFRESH_INTERVAL = 1_000_000_000L;

    /** Timestamp for the next allowed refresh rate detection. */
    private static long nextRefresh;

    /** Cached monitor refresh rate. */
    private static int monitorRefresh;

    /** Cached window bounds corresponding to the detected refresh rate. */
    private static Window measuredWindow;

    /** Asynchronous future for monitor sample detection. */
    private static Future<MonitorSample> refresh;

    /** Cached native window bounds for SDL polling. */
    private static Window nativeWindow;

    /** Timestamp for the next native refresh rate check. */
    private static long nextNativeRefresh;

    /** Cached native refresh rate value. */
    private static int nativeRefresh;

    /**
     * Record representing a 2D window bounding box.
     *
     * @param x window x position
     * @param y window y position
     * @param width window width
     * @param height window height
     */
    private record Window(int x, int y, int width, int height) {
    }

    /**
     * Record representing a sampled monitor refresh rate associated with window coordinates.
     *
     * @param window window position bounds
     * @param rate refresh rate frequency
     */
    private record MonitorSample(Window window, int rate) {
    }

    private ViewerFramerate() {}

    /**
     * Checks whether the viewer framerate limiter is currently active.
     *
     * @return {@code true} if active, {@code false} otherwise
     */
    public static boolean isActive() {
        return PeriodicTableScreen.isAvogadroMenuOpen();
    }

    /** Prewarms and schedules refresh rate detection if uninitialized. */
    static void prewarm() {
        if (!Display.isCreated())
            return;
        Window window = new Window(Display.getX(), Display.getY(), Display.getWidth(), Display.getHeight());
        long now = System.nanoTime();
        if (nativeRefreshRate(window, now) == 0 && refresh == null)
            scheduleRefresh(window, now);
    }

    /**
     * Determines the optimal framerate limit for the viewer menus.
     *
     * @return target framerate limit
     */
    public static int limit() {
        int configured = Config.VIEWER_FPS.get();
        if (configured > 0)
            return configured;
        Window window = new Window(Display.getX(), Display.getY(), Display.getWidth(), Display.getHeight());
        long now = System.nanoTime();
        int nativeRate = nativeRefreshRate(window, now);
        if (nativeRate > 0)
            return nativeRate;
        if (refresh != null && refresh.isDone()) {
            try {
                MonitorSample sample = refresh.get();
                if (window.equals(sample.window())) {
                    measuredWindow = sample.window();
                    monitorRefresh = sample.rate();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | CancellationException ignored) {
                // Headless launchers can still use LWJGL's display mode.
            }
            refresh = null;
        }
        if (refresh == null && now >= nextRefresh) {
            scheduleRefresh(window, now);
        }
        if (window.equals(measuredWindow) && monitorRefresh > 0)
            return monitorRefresh;
        int fallback = Display.getDisplayMode().getFrequency();
        return fallback > 0 ? fallback : 60;
    }

    /**
     * Schedules asynchronous refresh rate detection for the given window position.
     *
     * @param window window position bounds
     * @param now current timestamp
     */
    private static void scheduleRefresh(Window window, long now) {
        refresh = GeometryPreparation.submit(() -> new MonitorSample(window, detectRefreshRate(window)));
        nextRefresh = now + REFRESH_INTERVAL;
    }

    /**
     * Queries the native SDL display mode refresh rate.
     *
     * @param window window position bounds
     * @param now current timestamp
     * @return native refresh rate, or {@code 0} if unavailable
     */
    private static int nativeRefreshRate(Window window, long now) {
        if (window.equals(nativeWindow) && now < nextNativeRefresh)
            return nativeRefresh;
        nativeWindow = window;
        nextNativeRefresh = now + REFRESH_INTERVAL;
        nativeRefresh = 0;
        try {
            // The host already owns an SDL window. Query it without starting
            // AWT/D3D; SDL also tracks the monitor containing a moved window.
            long handle = LWJGLY.windowBridge().handle();
            if (handle != 0) {
                int display = SDLVideo.SDL_GetDisplayForWindow(handle);
                var mode = display == 0 ? null : SDLVideo.SDL_GetCurrentDisplayMode(display);
                if (mode != null && Float.isFinite(mode.refresh_rate()) && mode.refresh_rate() > 0)
                    nativeRefresh = Math.max(1, Math.round(mode.refresh_rate()));
            }
        } catch (LinkageError | RuntimeException unavailable) {
            // Legacy window providers use the background fallback instead.
        }
        return nativeRefresh;
    }

    /**
     * Detects the monitor refresh rate corresponding to the window position using AWT.
     *
     * @param position window bounds
     * @return detected refresh rate frequency, or {@code 0}
     */
    private static int detectRefreshRate(Window position) {
        // A window can span monitors. Use the one containing most of its area.
        // AWT's first monitor query can initialize native display services, so
        // this method only runs on a worker and never reads the live GL window.
        try {
            Rectangle window = new Rectangle(position.x(), position.y(), position.width(), position.height());
            long largestArea = -1;
            int refresh = 0;
            for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                Rectangle overlap = window.intersection(device.getDefaultConfiguration().getBounds());
                long area = overlap.isEmpty() ? 0 : (long) overlap.width * overlap.height;
                if (area > largestArea) {
                    largestArea = area;
                    refresh = device.getDisplayMode().getRefreshRate();
                }
            }
            if (refresh > 0)
                return refresh;
        } catch (RuntimeException ignored) {
            // Some launchers run with AWT disabled.
        }
        return 0;
    }
}

package io.github.wurtzitane.avogadro.client;

import io.github.wurtzitane.avogadro.Config;

/**
 * Manages cooperative performance and frame time budgets shared by uploads, previews, and export work.
 * <p>Driver calls are indivisible.
 */
public final class FrameWorkBudget {

    /** Maximum chunk size for GPU data uploads in bytes (256 KiB). */
    static final int UPLOAD_CHUNK_BYTES = 256 * 1024;

    /** Maximum total upload bytes allowed per frame (1 MiB). */
    private static final int MAX_UPLOAD_BYTES = 1024 * 1024;

    /** Maximum allowed nanosecond budget slice per frame (2 ms). */
    private static final long MAX_NANOS = 2_000_000L;

    /** Budget allowance, spent time counter, and export reservation trackers. */
    private static long allowance = MAX_NANOS, spent, exportReservation;

    /** Estimated nanosecond costs for uploads, animations, and exports. */
    private static long uploadEstimate = 100_000L, animationEstimate = 250_000L, exportEstimate = 100_000L;

    /** Counters tracking uploaded bytes, step counts, captures, and export steps. */
    private static int uploadedBytes, uploadSteps, captures, exportSteps;

    /** Flags indicating active frame and exporting status. */
    private static boolean active, exporting;

    /** Private constructor to prevent instantiation of utility class. */
    private FrameWorkBudget() {}

    /**
     * Begins a new frame budget calculation given the target frame rate.
     *
     * @param targetFps target frames per second
     */
    static void beginFrame(int targetFps) {
        allowance = Math.min(MAX_NANOS, 1_000_000_000L / Math.max(1, targetFps) / 4);
        spent = 0;
        uploadedBytes = uploadSteps = captures = exportSteps = 0;
        exporting = false;
        exportReservation = io.github.wurtzitane.avogadro.client.export.MoleculeMediaExport.hasReadyCaptureWork()
                && Config.EXPORT_PERFORMANCE.get() == Config.ExportPerformance.SMOOTH_GAMEPLAY ? allowance / 2 : 0;
        active = true;
    }

    /** Ends the current frame budget scope. */
    static void endFrame() {
        active = exporting = false;
    }

    /** Resets budget estimates and counters. */
    static void reset() {
        uploadEstimate = exportEstimate = 100_000L;
        animationEstimate = 250_000L;
        if (!active) {
            spent = 0;
            uploadedBytes = uploadSteps = captures = exportSteps = 0;
        }
    }

    /**
     * Attempts to allocate budget for a GPU data upload chunk.
     *
     * @param byteCount number of bytes to upload
     * @return {@code true} if allowed, {@code false} otherwise
     */
    static boolean tryUpload(int byteCount) {
        if (byteCount < 0 || byteCount > UPLOAD_CHUNK_BYTES) {
            return false;
        }
        if (!active) {
            return true;
        }
        if (exporting && fastest()) {
            return true;
        }
        if (uploadSteps >= 8 || uploadedBytes + byteCount > MAX_UPLOAD_BYTES || !hasTime(uploadEstimate)) {
            return false;
        }
        uploadedBytes += byteCount;
        uploadSteps++;
        return true;
    }

    /** Records elapsed time for a completed upload operation. */
    static void recordUpload(long elapsedNanos) {
        spend(elapsedNanos);
        uploadEstimate = estimate(uploadEstimate, elapsedNanos);
    }

    /** Attempts to allocate budget for an animation frame update. */
    static boolean tryAnimation() {
        if (!active) {
            return true;
        }
        if (captures != 0 || !hasTime(animationEstimate)) {
            return false;
        }
        captures++;
        return true;
    }

    /** Records elapsed time for a completed animation update. */
    static void recordAnimation(long elapsedNanos) {
        spend(elapsedNanos);
        animationEstimate = estimate(animationEstimate, elapsedNanos);
    }

    /**
     * Marks the beginning of media export work.
     * <p>Called once, inside the client render tick and before endFrame.
     */
    public static void beginExport() {
        exporting = true;
        exportReservation = 0;
    }

    /** Marks the end of media export work. */
    public static void endExport() {
        exporting = false;
    }

    /** Checks whether export operations can proceed within the current budget. */
    public static boolean canExport() {
        return !active || fastest() || exportSteps == 0 || spent + exportEstimate <= allowance;
    }

    /** Attempts to allocate budget for an export step. */
    public static boolean tryExport() {
        if (!canExport()) {
            return false;
        }
        exportSteps++;
        return true;
    }

    /** Records elapsed time for a completed export operation. */
    public static void recordExport(long elapsedNanos) {
        spend(elapsedNanos);
        exportEstimate = estimate(exportEstimate, elapsedNanos);
    }

    /** Checks whether export performance is set to fastest export mode. */
    public static boolean fastest() {
        return Config.EXPORT_PERFORMANCE.get() == Config.ExportPerformance.FASTEST_EXPORT;
    }

    /** Checks if sufficient time remains in the frame budget for an estimated task. */
    private static boolean hasTime(long estimate) {
        long available = allowance - (exporting ? 0 : exportReservation);
        return uploadSteps == 0 && captures == 0 && exportSteps == 0 || spent + estimate <= available;
    }

    /** Spends budget time and records overshoot metrics if limits are exceeded. */
    private static void spend(long nanos) {
        long before = spent;
        spent += Math.max(0, nanos);
        if (active && !fastest() && spent > allowance) {
            io.github.wurtzitane.avogadro.client.export.ExportMetrics.record(
                    io.github.wurtzitane.avogadro.client.export.ExportMetrics.Stage.BUDGET_OVERSHOOT,
                    spent - Math.max(before, allowance));
        }
    }

    /** Updates running time estimates using exponential smoothing. */
    private static long estimate(long previous, long elapsed) {
        return Math.max(1, previous * 3 / 4 + Math.max(0, elapsed) / 4);
    }
}
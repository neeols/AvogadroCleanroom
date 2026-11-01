package io.github.wurtzitane.avogadro.client.export;

import org.lwjgl.opengl.*;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Optional, bounded diagnostics.
 * <p>No output files or blocking GPU queries in normal exports.
 */
public final class ExportMetrics {

    /**
     * Enumeration of profiled export processing stages.
     */
    public enum Stage {
        PREPARATION, DRAW_SUBMISSION, GPU_ELAPSED, FENCE_LATENCY, MAP_COPY,
        PIXEL_CONVERSION, GIF_QUANTIZE, GIF_PALETTE, GIF_DITHER, GIF_WRITE, MP4_PIPE, FINALIZATION, BUDGET_OVERSHOOT
    }

    /** Flag indicating whether profiling metrics are enabled via system properties. */
    private static final boolean ENABLED = Boolean.getBoolean("avogadro.export.profile");

    /** Array of performance samples corresponding to each {@link Stage}. */
    private static final Sample[] SAMPLES = new Sample[Stage.values().length];

    /** Deque tracking pending asynchronous GPU queries. */
    private static final ArrayDeque<Integer> GPU_PENDING = new ArrayDeque<>(8);

    /** Active OpenGL capabilities context for GPU profiling. */
    private static GLCapabilities gpuContext;

    /** Status string describing GPU profiling support. */
    private static String gpuStatus = "not-observed";

    /** Counter tracking dropped GPU performance samples due to queue saturation. */
    private static long gpuDropped;

    static {
        for (int i = 0; i < SAMPLES.length; i++) {
            SAMPLES[i] = new Sample();
        }
    }

    /**
     * Private constructor to prevent instantiation.
     */
    private ExportMetrics() {}

    /**
     * Checks whether export metrics profiling is enabled.
     *
     * @return {@code true} if profiling is enabled, {@code false} otherwise
     */
    public static boolean enabled() {
        return ENABLED;
    }

    /**
     * Resets all metric samples and counters if profiling is enabled.
     */
    public static void reset() {
        if (!ENABLED) {
            return;
        }
        for (Sample sample : SAMPLES) {
            sample.clear();
        }
        gpuDropped = 0;
    }

    /**
     * Starts a high-resolution time measurement if profiling is enabled.
     *
     * @return start time in nanoseconds, or {@code 0} if disabled
     */
    static long start() {
        return ENABLED ? System.nanoTime() : 0;
    }

    /**
     * Records elapsed time for a specific stage given a start timestamp.
     *
     * @param stage the export {@link Stage}
     * @param start the start timestamp in nanoseconds
     */
    static void elapsed(Stage stage, long start) {
        if (start != 0) {
            record(stage, System.nanoTime() - start);
        }
    }

    /**
     * Records a raw duration in nanoseconds for a specific export stage.
     *
     * @param stage the export {@link Stage}
     * @param nanos duration in nanoseconds
     */
    public static void record(Stage stage, long nanos) {
        if (ENABLED && nanos >= 0) {
            SAMPLES[stage.ordinal()].add(nanos);
        }
    }

    /**
     * Begins an asynchronous GPU elapsed time query.
     * <p>All GPU methods are render-thread-only; query results are never waited for.
     *
     * @return the generated OpenGL query object ID, or {@code 0} if disabled or unavailable
     */
    static int gpuBegin() {
        if (!ENABLED) {
            return 0;
        }
        GLCapabilities capabilities = GL.getCapabilities();
        if (gpuContext != capabilities) {
            GPU_PENDING.clear();
            gpuContext = capabilities;
            gpuStatus = capabilities.OpenGL33 || capabilities.GL_ARB_timer_query ? "supported" : "unavailable";
        }
        pollGpu();
        if (!gpuStatus.equals("supported")) {
            return 0;
        }
        if (GPU_PENDING.size() >= 8) {
            gpuDropped++;
            return 0;
        }
        int query = GL15.glGenQueries();
        GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, query);
        return query;
    }

    /**
     * Ends the active GPU elapsed time query and adds it to the pending queue.
     *
     * @param query the OpenGL query object ID
     */
    static void gpuEnd(int query) {
        if (query == 0) {
            return;
        }
        GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
        GPU_PENDING.addLast(query);
    }

    /**
     * Polls pending GPU queries non-blockingly, recording completed timings.
     */
    static void pollGpu() {
        if (!ENABLED || gpuContext != GL.getCapabilities()) {
            return;
        }
        while (!GPU_PENDING.isEmpty()) {
            int query = GPU_PENDING.peekFirst();
            if (GL15.glGetQueryObjecti(query, GL15.GL_QUERY_RESULT_AVAILABLE) == 0) {
                return;
            }
            long nanos = gpuContext.OpenGL33 ? GL33.glGetQueryObjectui64(query, GL15.GL_QUERY_RESULT)
                    : ARBTimerQuery.glGetQueryObjectui64(query, GL15.GL_QUERY_RESULT);
            record(Stage.GPU_ELAPSED, nanos);
            GPU_PENDING.removeFirst();
            GL15.glDeleteQueries(query);
        }
    }

    /**
     * Closes and cleans up any remaining pending GPU queries.
     */
    static void closeGpu() {
        if (!ENABLED || gpuContext != GL.getCapabilities()) {
            return;
        }
        pollGpu();
        while (!GPU_PENDING.isEmpty()) {
            GL15.glDeleteQueries(GPU_PENDING.removeFirst());
        }
    }

    /**
     * Generates a snapshot map of all collected export metrics and percentiles.
     *
     * @return a map containing metric details and stage statistics
     */
    public static Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", ENABLED);
        result.put("gpuTiming", gpuStatus);
        result.put("gpuSamplesDropped", gpuDropped);
        result.put("percentiles", "upper bounds from fixed logarithmic histogram; nanoseconds");
        Map<String, Object> stages = new LinkedHashMap<>();
        for (Stage stage : Stage.values()) {
            stages.put(stage.name().toLowerCase(java.util.Locale.ROOT), SAMPLES[stage.ordinal()].snapshot());
        }
        result.put("stages", stages);
        return result;
    }

    /**
     * Bounded logarithmic histogram sample tracker for timing distributions.
     */
    private static final class Sample {

        /** Logarithmic histogram buckets for nanosecond durations. */
        private final long[] histogram = new long[64];

        /** Total count, cumulative time total, and maximum recorded duration. */
        private long count, total, max;

        /**
         * Clears all recorded samples in the histogram.
         */
        synchronized void clear() {
            java.util.Arrays.fill(histogram, 0);
            count = total = max = 0;
        }

        /**
         * Adds a duration sample in nanoseconds to the histogram.
         *
         * @param nanos duration in nanoseconds
         */
        synchronized void add(long nanos) {
            histogram[nanos == 0 ? 0 : 64 - Long.numberOfLeadingZeros(nanos)]++;
            count++;
            total = total > Long.MAX_VALUE - nanos ? Long.MAX_VALUE : total + nanos;
            max = Math.max(max, nanos);
        }

        /**
         * Generates a summary snapshot map for this sample bucket.
         *
         * @return map containing count, totals, maximums, and percentile upper bounds
         */
        synchronized Map<String, Object> snapshot() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("count", count);
            result.put("totalNanos", total);
            result.put("maxNanos", max);
            result.put("p50NanosUpperBound", percentile(.5));
            result.put("p95NanosUpperBound", percentile(.95));
            result.put("p99NanosUpperBound", percentile(.99));
            return result;
        }

        /**
         * Calculates the upper bound for a given percentile fraction from the histogram.
         *
         * @param fraction the percentile fraction (e.g. 0.95 for p95)
         * @return estimated nanosecond upper bound
         */
        private long percentile(double fraction) {
            if (count == 0) {
                return 0;
            }
            long wanted = (long) Math.ceil(count * fraction), seen = 0;
            for (int i = 0; i < histogram.length; i++) {
                if ((seen += histogram[i]) >= wanted) {
                    return i == 0 ? 0 : i >= 63 ? Long.MAX_VALUE : (1L << i) - 1;
                }
            }
            return max;
        }
    }
}
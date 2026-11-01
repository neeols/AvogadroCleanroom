package io.github.wurtzitane.avogadro.client.export;

import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Streams raw video frames directly to an explicitly installed FFmpeg process, without invoking a shell.
 */
public final class FfmpegMediaEncoder implements MediaEncoder {

    /** Maximum character limit maintained for captured process log output. */
    private static final int LOG_LIMIT = 16 * 1024;

    /** Timeout limit in seconds for FFmpeg probe commands. */
    private static final int PROBE_TIMEOUT_SECONDS = 10;

    /** Regular expression pattern for verifying libx264 encoder support in FFmpeg output. */
    private static final Pattern X264_HEADER = Pattern.compile("(?m)^Encoder libx264\\s");

    /** The running FFmpeg process instance. */
    private final Process process;

    /** Buffered output stream piped into FFmpeg standard input. */
    private final OutputStream input;

    /** Asynchronous process log reader for capturing error/diagnostic output. */
    private final ProcessLog log;

    /** Video width in pixels. */
    private final int width;

    /** Video height in pixels. */
    private final int height;

    /** Reusable pixel array buffer for row reads. */
    private final int[] pixels;

    /** Reusable byte array buffer for row byte serialization. */
    private final byte[] row;

    /** Counter tracking the number of frames successfully written. */
    private int frames;

    /** Flag indicating whether the encoder has been closed. */
    private volatile boolean closed;

    /** Flag indicating whether encoding has successfully finished. */
    private boolean finished;

    /**
     * Constructs a new {@link FfmpegMediaEncoder} starting an FFmpeg process writing to a temporary MP4 file.
     *
     * @param temporaryOutput path to the temporary output file
     * @param width           video width in pixels (must be an even number >= 2)
     * @param height          video height in pixels (must be an even number >= 2)
     * @param fps             frames per second
     * @param executable      path or command name of the FFmpeg executable
     * @throws IOException              if process startup or pipe opening fails
     * @throws IllegalArgumentException if dimensions are not even or frame rate is invalid
     */
    public FfmpegMediaEncoder(Path temporaryOutput, int width, int height, int fps, String executable) throws IOException {
        if (width < 2 || height < 2 || (width & 1) != 0 || (height & 1) != 0) {
            throw new IllegalArgumentException("H.264 dimensions must be positive even numbers");
        }
        if (fps < 1) {
            throw new IllegalArgumentException("MP4 frame rate must be a positive integer");
        }
        this.width = width;
        this.height = height;
        pixels = new int[width];
        row = new byte[Math.multiplyExact(width, 4)];
        process = new ProcessBuilder(executable, "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
                "-f", "rawvideo", "-pixel_format", "rgba", "-video_size", width + "x" + height,
                "-framerate", Integer.toString(fps), "-i", "pipe:0", "-an", "-c:v", "libx264",
                "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p", "-movflags", "+faststart",
                "-f", "mp4", temporaryOutput.toAbsolutePath().toString())
                .redirectErrorStream(true).start();
        log = new ProcessLog(process.getInputStream());
        input = new BufferedOutputStream(process.getOutputStream(), 64 * 1024);
    }

    /**
     * Resolves and checks the installed FFmpeg encoder before any frames are rendered.
     *
     * @param requested optional requested executable path or command name
     * @return the absolute, normalized path string of the validated FFmpeg executable
     * @throws IOException if FFmpeg cannot be found, is invalid, or lacks libx264 support
     */
    public static String resolveExecutable(String requested) throws IOException {
        String configured = requested == null || requested.isBlank() ? System.getenv("AVOGADRO_FFMPEG") : requested;
        String command = configured == null || configured.isBlank() ? "ffmpeg" : configured.strip();
        Path executable = findExecutable(command);
        String version = probe(executable, "-version");
        if (!version.toLowerCase(Locale.ROOT).startsWith("ffmpeg version ")) {
            throw new IOException("The selected executable is not FFmpeg: " + executable);
        }
        String encoder = probe(executable, "-hide_banner", "-h", "encoder=libx264");
        if (!X264_HEADER.matcher(encoder).find()) {
            throw new IOException("This FFmpeg build has no libx264 H.264 encoder. Install a build with libx264 or use GIF.");
        }
        return executable.toAbsolutePath().normalize().toString();
    }

    /**
     * Locates the executable file on disk or along the system PATH.
     *
     * @param command executable command name or path
     * @return the resolved executable {@link Path}
     * @throws IOException if the file does not exist or is not executable
     */
    private static Path findExecutable(String command) throws IOException {
        try {
            Path path = Path.of(command);
            if (path.isAbsolute() || path.getNameCount() > 1 || command.contains("\\") || command.contains("/")) {
                if (usable(path)) {
                    return path;
                }
            } else {
                boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
                String[] names = windows && !command.toLowerCase(Locale.ROOT).endsWith(".exe")
                        ? new String[]{command + ".exe", command} : new String[]{command};
                String search = System.getenv("PATH");
                if (search != null) {
                    for (String directory : search.split(Pattern.quote(java.io.File.pathSeparator))) {
                        if (directory.isBlank()) {
                            continue;
                        }
                        for (String name : names) {
                            Path candidate = Path.of(directory, name);
                            if (usable(candidate)) {
                                return candidate;
                            }
                        }
                    }
                }
            }
        } catch (InvalidPathException failure) {
            throw new IOException("Invalid FFmpeg executable path", failure);
        }
        throw new IOException("FFmpeg was not found. Install FFmpeg with libx264 and add it to PATH, "
                + "or supply --encoder <path-to-ffmpeg>. GIF needs no external encoder.");
    }

    /** Checks whether a path points to a regular, executable file. */
    private static boolean usable(Path path) {
        return Files.isRegularFile(path) && Files.isExecutable(path);
    }

    /** Probes FFmpeg with specific arguments to validate its build configuration. */
    private static String probe(Path executable, String... arguments) throws IOException {
        List<String> command = new ArrayList<>(arguments.length + 1);
        command.add(executable.toAbsolutePath().toString());
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ProcessLog log = new ProcessLog(process.getInputStream());
        try {
            process.getOutputStream().close();
            if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IOException("FFmpeg did not respond within " + PROBE_TIMEOUT_SECONDS + " seconds");
            }
            log.await();
            if (process.exitValue() != 0) {
                throw new IOException("FFmpeg validation failed: " + log.text());
            }
            return log.text();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("FFmpeg validation cancelled", failure);
        } finally {
            process.destroyForcibly();
            log.close();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write(BufferedImage frame, int frameIndex) throws IOException {
        checkOpen();
        if (frameIndex != frames) {
            throw new IOException("MP4 frames must be written in order");
        }
        if (frame.getWidth() != width || frame.getHeight() != height) {
            throw new IOException("MP4 frame dimensions changed");
        }
        try {
            int[] direct = MediaPixels.packedPixels(frame);
            for (int y = 0; y < height; y++) {
                if (closed || Thread.currentThread().isInterrupted()) {
                    throw new IOException("MP4 export cancelled");
                }
                if (direct == null) {
                    frame.getRGB(0, y, width, 1, pixels, 0, width);
                }
                for (int x = 0, offset = 0; x < width; x++) {
                    int pixel = direct == null ? pixels[x] : direct[y * width + x];
                    row[offset++] = (byte) (pixel >>> 16);
                    row[offset++] = (byte) (pixel >>> 8);
                    row[offset++] = (byte) pixel;
                    row[offset++] = (byte) (pixel >>> 24);
                }
                input.write(row);
            }
            frames++;
        } catch (IOException failure) {
            throw new IOException("FFmpeg could not encode frame " + frameIndex + ": " + log.text(), failure);
        }
    }

    /** The normal capture already owns opaque RGBA; preserve it without an ARGB round trip. */
    void write(MediaPixels frame, int frameIndex) throws IOException {
        long measured = ExportMetrics.start();
        checkOpen();
        if (frameIndex != frames) {
            throw new IOException("MP4 frames must be written in order");
        }
        if (frame.width != width || frame.height != height) {
            throw new IOException("MP4 frame dimensions changed");
        }
        if (frame.unpremultiply) {
            throw new IOException("MP4 requires an opaque captured frame");
        }
        try {
            writeRgba(frame, input);
            frames++;
        } catch (IOException failure) {
            throw new IOException("FFmpeg could not encode frame " + frameIndex + ": " + log.text(), failure);
        } finally {
            ExportMetrics.elapsed(ExportMetrics.Stage.MP4_PIPE, measured);
        }
    }

    /** Writes raw RGBA frame pixels to the output stream. */
    static void writeRgba(MediaPixels frame, OutputStream destination) throws IOException {
        int stride = frame.width * 4;
        for (int y = frame.height - 1; y >= 0; y--) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("MP4 export cancelled");
            }
            destination.write(frame.rgba, y * stride, stride);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void finish() throws IOException {
        long measured = ExportMetrics.start();
        checkOpen();
        if (frames == 0) {
            throw new IOException("Cannot finish an MP4 without frames");
        }
        input.close();
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                throw new IOException("FFmpeg did not finish within 60 seconds");
            }
            log.await();
            if (process.exitValue() != 0) {
                throw new IOException("FFmpeg encoding failed: " + log.text());
            }
            finished = true;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("MP4 export cancelled", failure);
        } finally {
            ExportMetrics.elapsed(ExportMetrics.Stage.FINALIZATION, measured);
        }
    }

    /** Checks whether the encoder stream is still open and active. */
    private void checkOpen() throws IOException {
        if (closed || finished) {
            throw new IOException("MP4 encoder is closed");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        // Destroy first: a cancelled write may be waiting for the pipe to drain.
        if (process.isAlive()) {
            process.destroyForcibly();
        }
        // Windows keeps an output file locked until the child has actually exited.
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try {
            while (process.isAlive()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    break;
                }
                try {
                    process.waitFor(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            try {
                input.close();
            } catch (IOException ignored) { }
            log.close();
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Asynchronous process log reader for capturing stderr/stdout from FFmpeg.
     */
    private static final class ProcessLog implements AutoCloseable {

        /** Input stream reading from the process. */
        private final InputStream stream;

        /** Bounded string builder retaining log tail output. */
        private final StringBuilder tail = new StringBuilder();

        /** Background daemon thread draining the process stream. */
        private final Thread reader;

        /**
         * Constructs a new {@link ProcessLog} and starts a background reader thread.
         *
         * @param stream process input stream
         */
        ProcessLog(InputStream stream) {
            this.stream = stream;
            reader = new Thread(this::drain, "Avogadro encoder log");
            reader.setDaemon(true);
            reader.start();
        }

        /** Continuously drains bytes from the process stream into the log tail. */
        private void drain() {
            byte[] bytes = new byte[2048];
            try {
                int read;
                while ((read = stream.read(bytes)) >= 0) {
                    synchronized (tail) {
                        tail.append(new String(bytes, 0, read, StandardCharsets.UTF_8));
                        if (tail.length() > LOG_LIMIT) {
                            tail.delete(0, tail.length() - LOG_LIMIT);
                        }
                    }
                }
            } catch (IOException ignored) { }
        }

        /** Retrieves the stripped process log text. */
        String text() {
            synchronized (tail) {
                return tail.toString().strip();
            }
        }

        /** Awaits termination of the reader thread. */
        void await() throws InterruptedException {
            reader.join(2000);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void close() {
            try {
                stream.close();
            } catch (IOException ignored) { }
        }
    }
}
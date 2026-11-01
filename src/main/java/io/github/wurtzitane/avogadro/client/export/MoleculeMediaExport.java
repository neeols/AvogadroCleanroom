package io.github.wurtzitane.avogadro.client.export;

import io.github.wurtzitane.avogadro.Avogadro;
import io.github.wurtzitane.avogadro.client.ElementPalette;
import io.github.wurtzitane.avogadro.api.client.*;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import io.github.wurtzitane.avogadro.molecule.MoleculeDef;
import net.minecraft.client.Minecraft;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.event.ClickEvent;
import io.github.wurtzitane.avogadro.client.FrameWorkBudget;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Manages asynchronous media export jobs, frame dispatching, and background encoding threads.
 * <p>Capture stays on the render thread; bounded background encoding never waits in a render tick.
 */
public final class MoleculeMediaExport {

    /** Chat message ID used for updating ongoing export progress messages. */
    private static final int PROGRESS_MESSAGE = 0x41564F;

    /** Currently active export job instance. */
    private static volatile Job active;

    /** Path to the last successfully saved export file. */
    private static volatile Path lastOutput;

    /** Error message string of the last failed export job. */
    private static volatile String lastFailure;

    /** Current human-readable export status string. */
    private static volatile String status = "No molecule export is running.";

    /**
     * Starts a media export job for a list of molecule definitions.
     *
     * @param options     export configuration options
     * @param definitions list of molecule definitions to export
     * @throws IOException if an export is already running or file creation fails
     */
    public static void start(ExportOptions options, List<MoleculeDef> definitions) throws IOException {
        submit(MediaExportRequest.molecules(definitions).options(options).build(), true);
    }

    /**
     * Starts an item card media export job.
     *
     * @param item item export options configuration
     * @throws IOException if an export is already running or file creation fails
     */
    public static void startItem(ItemExportOptions item) throws IOException {
        submit(MediaExportRequest.item(item).build(), true);
    }

    /**
     * Starts an atom inspection media export job.
     *
     * @param options export configuration options
     * @param atom    atom export options configuration
     * @throws IOException if an export is already running or file creation fails
     */
    public static void startAtom(ExportOptions options, AtomExportOptions atom) throws IOException {
        submit(MediaExportRequest.atom(atom).options(options).build(), true);
    }

    /**
     * Submits a media export request and returns its export job handle.
     *
     * @param request the media export request
     * @return an {@link ExportJob} handle for monitoring
     * @throws IOException if submission fails
     */
    public static ExportJob submit(MediaExportRequest request) throws IOException {
        return submit(request, false);
    }

    /** Submits a media export request with optional chat notifications. */
    private static ExportJob submit(MediaExportRequest request, boolean chat) throws IOException {
        Minecraft client = Minecraft.getMinecraft();
        if (!client.isCallingFromMinecraftThread()) {
            throw new IllegalStateException("Export submission requires the client thread");
        }
        java.util.Objects.requireNonNull(request, "request");
        if (active != null) {
            throw new IOException("An export is already running.");
        }
        ExportOptions options = request.options();
        List<MoleculeDef> definitions = request.definitions();
        ItemExportOptions item = request.item();
        Path directory = client.gameDir.toPath().resolve("avogadro-exports");
        Files.createDirectories(directory);
        String filename = options.filename();
        if (filename == null) {
            String label = item != null ? "item-" + item.selection().stack().getItem().getRegistryName().toString().replace(':', '_')
                                          + "-" + item.selection().stack().getMetadata()
                    : definitions.size() == 1 ? definitions.getFirst().id().getPath().replace('/', '_') : "all-molecules";
            filename = label + "-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").format(LocalDateTime.now())
                    + "." + options.format().extension();
        }
        Path output = directory.resolve(filename);
        if (Files.exists(output)) {
            throw new IOException("File already exists: " + output);
        }
        Path reproduction = request.reproduction() == null ? null : directory.resolve(filename + ".json");
        if (reproduction != null && Files.exists(reproduction)) {
            throw new IOException("File already exists: " + reproduction);
        }
        List<MoleculeDef> snapshot = definitions.stream().sorted(Comparator.comparing(value -> value.id().toString())).toList();
        ExportMetrics.reset();
        Job job = new Job(request, snapshot, output, reproduction, chat);
        Thread worker = new Thread(() -> encode(job), "Avogadro media encoder");
        job.worker = worker;
        worker.setDaemon(true);
        active = job;
        lastOutput = null;
        lastFailure = null;
        status = "Preparing " + options.format() + " export...";
        if (chat) {
            message(status);
        }
        try {
            worker.start();
        } catch (RuntimeException | Error failure) {
            active = null;
            throw failure;
        }
        return job.handle;
    }

    /** Checks whether an export job is currently running. */
    public static booleanisRunning() {
        return active != null;
    }

    /** Checks whether there is ready capture work available in the active queue. */
    public static boolean hasReadyCaptureWork() {
        Job job = active;
        return job != null && job.ready && !job.cancelled && !job.done && job.captured < job.options.totalFrames() && job.frames.remainingCapacity() != 0;
    }

    /** Retrieves the output path of the last completed export. */
    public static Path lastOutput() {
        return lastOutput;
    }

    /** Retrieves the error message of the last failed export. */
    public static String lastFailure() {
        return lastFailure;
    }

    /** Retrieves the current export status string. */
    public static String status() {
        return status;
    }

    /** Cancels the currently active export job. */
    public static boolean cancel() {
        Job job = active;
        return job != null && cancel(job);
    }

    /** Cancels a specific export job instance. */
    private static boolean cancel(Job job) {
        synchronized (job) {
            if (job.published || job.done || job.cancelled || active != job) {
                return false;
            }
            job.cancelled = true;
        }
        job.handle.update(MediaExportJob.State.CANCELLING, job.captured, job.encoded);
        job.worker.interrupt();
        status = "Cancelling molecule export...";
        // Closing native encoder I/O must not block the calling thread.
        Thread closer = new Thread(() -> {
            MediaEncoder encoder = job.encoder;
            if (encoder instanceof FfmpegMediaEncoder) {
                encoder.close();
            }
        }, "Avogadro media cancellation");
        closer.setDaemon(true);
        closer.start();
        return true;
    }

    /** Advances active export frame capture and completion checks on each client tick. */
    public static void advance() {
        Job job = active;
        if (job == null) {
            return;
        }
        if (job.done) {
            try {
                if (job.capture != null) {
                    job.capture.close();
                }
            } catch (Throwable failure) {
                job.failure = "Could not release export capture: " + explanation(failure);
            }
            active = null;
            MediaExportJob.State terminal;
            if (job.failure != null) {
                terminal = MediaExportJob.State.FAILED;
                lastFailure = job.failure;
                status = "Export failed: " + job.failure;
                if (job.chat) {
                    message(status);
                }
            } else if (job.cancelled) {
                terminal = MediaExportJob.State.CANCELLED;
                status = "Molecule export cancelled; temporary files removed.";
                if (job.chat) {
                    message(status);
                }
            } else {
                terminal = MediaExportJob.State.SUCCEEDED;
                lastOutput = job.output;
                status = "Saved molecule export: " + job.output;
                if (job.chat) {
                    TextComponentString saved = new TextComponentString("Avogadro: " + status);
                    saved.getStyle().setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, job.output.toString()));
                    Minecraft.getMinecraft().ingameGUI.getChatGUI().printChatMessageWithOptionalDeletion(saved, PROGRESS_MESSAGE);
                }
                Avogadro.LOGGER.info(status);
            }
            job.handle.complete(new MediaExportJob.Result(terminal,
                    terminal == MediaExportJob.State.SUCCEEDED ? job.output : null,
                    terminal == MediaExportJob.State.SUCCEEDED ? job.reproductionPath : null, job.failure), job.captured, job.encoded);
            return;
        }
        job.handle.update(job.cancelled ? MediaExportJob.State.CANCELLING :
                job.ready ? MediaExportJob.State.RUNNING : MediaExportJob.State.PREPARING, job.captured, job.encoded);
        if (job.cancelled || !job.ready || job.captured == job.options.totalFrames() || job.frames.remainingCapacity() == 0) {
            return;
        }
        try {
            ExportMetrics.pollGpu();
            if (!FrameWorkBudget.canExport()) {
                return;
            }
            if (job.capture == null) {
                job.capture = new MediaFrameCapture(job.options, job.definitions, job.item, job.artwork, job.palette, job.atom, job.rendering);
                job.artwork = null;
            }
            MediaPixels pixels;
            do {
                pixels = job.capture.advancePixels(job.captured);
                if (pixels != null) {
                    synchronized (job) {
                        if (job.ready && !job.cancelled) {
                            job.frames.add(new Frame(job.captured++, pixels, job.capture.protectedColors()));
                        } else {
                            job.capture.recycle(pixels);
                        }
                    }
                }
            } while (FrameWorkBudget.fastest() && pixels != null && hasReadyCaptureWork());
            if (System.nanoTime() - job.lastProgress > 1_000_000_000L) {
                job.lastProgress = System.nanoTime();
                status = "Exporting " + job.options.format() + " at " + job.options.fps() + " fps: " + job.encoded + "/" + job.options.totalFrames()
                        + " frames. /avogadro export cancel";
                if (job.chat) {
                    message(status);
                }
            }
        } catch (Throwable failure) {
            job.failure = explanation(failure);
            Avogadro.LOGGER.error("Cannot capture molecule media", failure);
            cancel(job);
        }
    }

    /** Background worker method executing media encoding. */
    private static void encode(Job job) {
        Path temporary = null, reproductionTemporary = null;
        boolean sidecarPublished = false;
        try {
            String executable = job.options.format() == ExportOptions.Format.MP4
                    ? FfmpegMediaEncoder.resolveExecutable(job.options.encoder()) : null;
            if (job.cancelled) {
                return;
            }
            temporary = Files.createTempFile(job.output.getParent(), ".avogadro-", "." + job.options.format().extension());
            if (job.item != null) {
                job.artwork = TooltipCardArtwork.create(job.options.width(), job.options.height(), job.item.scale(), job.options.background());
            }
            job.ready = true;
            Frame first = null;
            while (!job.cancelled && first == null) {
                first = job.frames.poll(250, TimeUnit.MILLISECONDS);
            }
            if (job.cancelled) {
                return;
            }
            try (MediaEncoder encoder = executable == null
                    ? new GifMediaEncoder(temporary, job.options.fps(), job.options.background() == null, first.protectedColors())
                    : new FfmpegMediaEncoder(temporary, job.options.width(), job.options.height(), job.options.fps(), executable)) {
                job.encoder = encoder;
                if (encoder instanceof GifMediaEncoder gif) {
                    try (GifFramePipeline pipeline = new GifFramePipeline(gif,
                            GifFramePipeline.workerCount(job.options.width(), job.options.height()))) {
                        Frame frame = first;
                        int submitted = 0;
                        while (!job.cancelled && submitted < job.options.totalFrames()) {
                            if (frame == null) {
                                frame = job.frames.poll(250, TimeUnit.MILLISECONDS);
                            }
                            if (frame == null) {
                                continue;
                            }
                            MediaPixels pixels = frame.pixels();
                            pipeline.write(pixels, frame.index(), () -> job.capture.recycle(pixels));
                            submitted++;
                            job.encoded = pipeline.written();
                            frame = null;
                        }
                        if (job.cancelled) {
                            return;
                        }
                        pipeline.finish();
                        job.encoded = submitted;
                    }
                } else {
                    FfmpegMediaEncoder mp4 = (FfmpegMediaEncoder) encoder;
                    Frame frame = first;
                    while (!job.cancelled && job.encoded < job.options.totalFrames()) {
                        if (frame == null) {
                            frame = job.frames.poll(250, TimeUnit.MILLISECONDS);
                        }
                        if (frame == null) {
                            continue;
                        }
                        try {
                            mp4.write(frame.pixels(), frame.index());
                        } finally {
                            job.capture.recycle(frame.pixels());
                        }
                        job.encoded++;
                        frame = null;
                    }
                    if (job.cancelled) {
                        return;
                    }
                    encoder.finish();
                }
            } finally {
                job.encoder = null;
            }
            if (job.reproduction != null) {
                reproductionTemporary = Files.createTempFile(job.output.getParent(), ".avogadro-", ".json");
                Files.writeString(reproductionTemporary, job.reproduction.toString(), StandardCharsets.UTF_8);
            }
            synchronized (job) {
                if (job.cancelled) {
                    return;
                }
                if (reproductionTemporary != null) {
                    Files.move(reproductionTemporary, job.reproductionPath);
                    reproductionTemporary = null;
                    sidecarPublished = true;
                }
                // Without REPLACE_EXISTING, even a file created during capture is protected.
                Files.move(temporary, job.output);
                temporary = null;
                job.published = true;
            }
        } catch (Throwable failure) {
            if (!job.cancelled) {
                job.failure = explanation(failure);
                Avogadro.LOGGER.error("Cannot encode molecule media", failure);
            }
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException failure) {
                    job.failure = "Could not remove temporary export " + temporary + ": " + explanation(failure);
                    Avogadro.LOGGER.warn(job.failure, failure);
                }
            }
            if (reproductionTemporary != null) {
                try {
                    Files.deleteIfExists(reproductionTemporary);
                } catch (IOException failure) {
                    job.failure = "Could not remove temporary reproduction: " + explanation(failure);
                }
            }
            if (sidecarPublished && !job.published) {
                try {
                    Files.deleteIfExists(job.reproductionPath);
                } catch (IOException failure) {
                    job.failure = "Could not remove incomplete reproduction: " + explanation(failure);
                }
            }
            synchronized (job) {
                job.ready = false;
                if (job.capture != null) {
                    job.capture.discardFrames(job.frames, Frame::pixels);
                } else {
                    job.frames.clear();
                }
            }
            job.done = true;
        }
    }

    /** Extracts a clean error explanation message from a throwable. */
    private static String explanation(Throwable failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    /** Sends a chat message to the client. */
    private static void message(String message) {
        Minecraft.getMinecraft().ingameGUI.getChatGUI().printChatMessageWithOptionalDeletion(
                new TextComponentString("Avogadro: " + message), PROGRESS_MESSAGE);
    }

    /** Record representing an enqueued frame package. */
    private record Frame(int index, MediaPixels pixels, int[] protectedColors) {}

    /** Internal job state container tracking an export operation. */
    private static final class Job {
        final ExportOptions options;
        final List<MoleculeDef> definitions;
        final Path output;
        final ItemExportOptions item;
        final AtomExportOptions atom;
        final MoleculeRenderOptions rendering;
        final JsonObject reproduction;
        final Path reproductionPath;
        final boolean chat;
        final ExportJobHandle handle;
        final ElementPalette.Snapshot palette;
        TooltipCardArtwork.Image artwork;
        final ArrayBlockingQueue<Frame> frames = new ArrayBlockingQueue<>(2);
        MediaFrameCapture capture;
        volatile int captured;
        long lastProgress;
        volatile int encoded;
        Thread worker;
        volatile boolean ready, cancelled, done, published;
        volatile String failure;
        volatile MediaEncoder encoder;

        /** Constructs a new {@link Job} context. */
        Job(MediaExportRequest request, List<MoleculeDef> definitions, Path output, Path reproductionPath, boolean chat) {
            this.options = request.options();
            this.definitions = definitions;
            this.output = output;
            this.item = request.item();
            this.atom = request.atom();
            this.rendering = request.renderOptions();
            this.reproduction = request.reproduction();
            this.reproductionPath = reproductionPath;
            this.chat = chat;
            palette = rendering == null ? ElementPalette.snapshot() : rendering.palette();

            handle = new ExportJobHandle(options.totalFrames(), action -> Minecraft.getMinecraft().addScheduledTask(action),
                    () -> MoleculeMediaExport.cancel(this), failure -> Avogadro.LOGGER.error("Export completion listener failed", failure));
        }
    }
}
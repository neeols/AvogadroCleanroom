package io.github.wurtzitane.avogadro.client.export;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.github.wurtzitane.avogadro.Config;

/**
 * Validated, immutable settings for one export.
 * <p>
 * Saved appearance settings are only read.
 *
 * @param format the export media format (GIF or MP4)
 * @param width the canvas width in pixels
 * @param height the canvas height in pixels
 * @param fps the target frames per second
 * @param secondsPerRotation the duration of each complete rotation in seconds
 * @param rotations the number of complete rotations
 * @param showName {@code true} to display the molecule name label
 * @param showFormula {@code true} to display the chemical formula label
 * @param background optional RGB background color integer, or {@code null} for transparency
 * @param direction the rotation direction (forward or reverse)
 * @param cubic {@code true} for cubic atom rendering style, {@code false} for spherical
 * @param columns the number of columns in a gallery export grid
 * @param filename the validated output filename
 * @param encoder optional path to an external FFmpeg encoder executable
 * @param moleculeCount total number of molecules being exported
 * @param textScale the scale factor applied to text labels
 */
public record ExportOptions(Format format, int width, int height, int fps, double secondsPerRotation, int rotations,
        boolean showName, boolean showFormula, Integer background, Config.RotationDirection direction, boolean cubic,
        int columns, String filename, String encoder, int moleculeCount, double textScale) {

    /** Maximum allowed molecules in a single export gallery. */
    public static final int MAX_MOLECULES = 256;

    /** Maximum allowed total pixels in an export canvas (8,388,608 pixels). */
    public static final long MAX_PIXELS = 8L * 1024 * 1024;

    /** Maximum allowed frames in an exported animation. */
    public static final int MAX_FRAMES = 7200;

    /** Set of supported command-line option flag names. */
    private static final Set<String> FLAGS = Set.of("format", "width", "height", "fps", "speed", "rotations", "name",
            "formula", "background", "direction", "appearance", "columns", "output", "encoder", "text-scale");

    /**
     * Creates a {@link ExportOptions} using the default text scale configuration.
     *
     * @param format export media format
     * @param width canvas width in pixels
     * @param height canvas height in pixels
     * @param fps frames per second
     * @param secondsPerRotation duration of each rotation in seconds
     * @param rotations number of complete rotations
     * @param showName {@code true} to display molecule name
     * @param showFormula {@code true} to display chemical formula
     * @param background optional RGB background color integer
     * @param direction {@link Config.RotationDirection}
     * @param cubic {@code true} for cubic atoms, {@code false} for spherical
     * @param columns gallery column count
     * @param filename output filename
     * @param encoder optional encoder executable path
     * @param moleculeCount total count of molecules being exported
     */
    public ExportOptions(Format format, int width, int height, int fps, double secondsPerRotation, int rotations,
            boolean showName, boolean showFormula, Integer background, Config.RotationDirection direction,
            boolean cubic, int columns, String filename, String encoder, int moleculeCount) {
        this(format, width, height, fps, secondsPerRotation, rotations, showName, showFormula, background, direction,
                cubic, columns, filename, encoder, moleculeCount, Config.TEXT_SCALE.get());
    }

    /**
     * Compact constructor for {@link ExportOptions} validating canvas sizes, galleries, frame rates, durations, and
     * formats.
     *
     * @throws IllegalArgumentException if any validation rule or export limit is violated
     */
    public ExportOptions {
        if (format == null || direction == null) {
            throw invalid("Format and rotation direction are required.");
        }
        if (moleculeCount < 1 || moleculeCount > MAX_MOLECULES) {
            throw invalid("Molecule count must be 1..256.");
        }
        if (width < 64 || width > 4096 || height < 64 || height > 4096 || (long) width * height > MAX_PIXELS) {
            throw invalid("Canvas dimensions must be 64..4096 and at most 8,388,608 pixels.");
        }
        if (columns < 1 || columns > 16 || columns > moleculeCount) {
            throw invalid("Invalid gallery column count.");
        }
        int rows = (moleculeCount + columns - 1) / columns;
        if (moleculeCount > 1 && (width / columns < 96 || height / rows < 96)) {
            throw invalid("Gallery cells require at least 96 pixels per dimension.");
        }
        if (fps < 1 || (format == Format.GIF && fps > 60) || !Double.isFinite(secondsPerRotation)
                || secondsPerRotation < .1 || secondsPerRotation > 600 || rotations < 1 || rotations > 100) {
            throw invalid("Invalid frame rate, duration, or rotation count.");
        }
        if (format == Format.GIF) {
            fps = Math.min(fps, 50);
        }
        double duration = secondsPerRotation * rotations;
        long frames = Math.round(duration * fps);
        if (duration > 600 || frames < 2L * rotations || frames > MAX_FRAMES) {
            throw invalid("Export duration or frame count exceeds its limits.");
        }
        if (background != null && (background < 0 || background > 0xFFFFFF)) {
            throw invalid("Export background must be RGB or null.");
        }
        if (format == Format.MP4 && (background == null || (width & 1) != 0 || (height & 1) != 0)) {
            throw invalid("MP4 requires even dimensions and a solid background.");
        }
        if (!Double.isFinite(textScale) || textScale < .5 || textScale > 3) {
            throw invalid("Text scale must be .5..3.");
        }
        if (encoder != null && (encoder.isBlank() || format != Format.MP4)) {
            throw invalid("An encoder executable is only valid for MP4.");
        }
        filename = filename(filename, format);
    }

    /**
     * Creates a new builder initialized with default export options for a specified molecule count.
     *
     * @param moleculeCount the number of molecules
     * @return a new {@link Builder}
     */
    public static Builder builder(int moleculeCount) {
        return new Builder(parse(new String[0], moleculeCount));
    }

    /**
     * Creates a builder pre-populated with values from this export options instance.
     *
     * @return a new {@link Builder}
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * Builder class for programmatically constructing export options.
     */
    public static final class Builder {
        private Format format;
        private int width, height, fps, rotations, columns, count;
        private double speed, textScale;
        private boolean showName, showFormula, cubic;
        private Integer background;
        private Config.RotationDirection direction;
        private String filename, encoder;

        /**
         * Constructs a builder from an existing export options instance.
         *
         * @param o the source {@link ExportOptions}
         */
        private Builder(ExportOptions o) {
            format = o.format;
            width = o.width;
            height = o.height;
            fps = o.fps;
            speed = o.secondsPerRotation;
            rotations = o.rotations;
            showName = o.showName;
            showFormula = o.showFormula;
            background = o.background;
            direction = o.direction;
            cubic = o.cubic;
            columns = o.columns;
            filename = o.filename;
            encoder = o.encoder;
            count = o.moleculeCount;
            textScale = o.textScale;
        }

        /** Sets the export media format. */
        public Builder format(Format value) {
            format = value;
            return this;
        }

        /** Sets the canvas width and height in pixels. */
        public Builder size(int width, int height) {
            this.width = width;
            this.height = height;
            return this;
        }

        /** Sets the target frames per second. */
        public Builder fps(int value) {
            fps = value;
            return this;
        }

        /** Sets the seconds per rotation duration. */
        public Builder secondsPerRotation(double value) {
            speed = value;
            return this;
        }

        /** Sets the number of complete rotations. */
        public Builder rotations(int value) {
            rotations = value;
            return this;
        }

        /** Sets whether to show the molecule name. */
        public Builder showName(boolean value) {
            showName = value;
            return this;
        }

        /** Sets whether to show the chemical formula. */
        public Builder showFormula(boolean value) {
            showFormula = value;
            return this;
        }

        /** Sets the RGB background color integer. */
        public Builder background(Integer rgb) {
            background = rgb;
            return this;
        }

        /** Sets the rotation direction. */
        public Builder direction(Config.RotationDirection value) {
            direction = value;
            return this;
        }

        /** Sets whether atoms are rendered in cubic style. */
        public Builder cubic(boolean value) {
            cubic = value;
            return this;
        }

        /** Sets the gallery column count. */
        public Builder columns(int value) {
            columns = value;
            return this;
        }

        /** Sets the output filename. */
        public Builder filename(String value) {
            filename = value;
            return this;
        }

        /** Sets the FFmpeg encoder executable path. */
        public Builder encoder(String value) {
            encoder = value;
            return this;
        }

        /** Sets the text scale factor. */
        public Builder textScale(double value) {
            textScale = value;
            return this;
        }

        /**
         * Builds and returns the immutable {@link ExportOptions}.
         *
         * @return a new {@link ExportOptions}
         */
        public ExportOptions build() {
            return new ExportOptions(format, width, height, fps, speed, rotations, showName, showFormula, background,
                    direction, cubic, columns, filename, encoder, count, textScale);
        }
    }

    /**
     * Supported export media formats.
     */
    public enum Format {
        GIF("gif"), MP4("mp4");

        private final String extension;

        Format(String extension) {
            this.extension = extension;
        }

        /**
         * Returns the file extension string for this format.
         *
         * @return the file extension
         */
        public String extension() {
            return extension;
        }
    }

    /**
     * Calculates total gallery rows.
     *
     * @return row count
     */
    public int rows() {
        return (moleculeCount + columns - 1) / columns;
    }

    /**
     * Calculates individual gallery cell width in pixels.
     *
     * @return cell width
     */
    public int cellWidth() {
        return width / columns;
    }

    /**
     * Calculates individual gallery cell height in pixels.
     *
     * @return cell height
     */
    public int cellHeight() {
        return height / rows();
    }

    /**
     * Calculates total frames in the exported animation.
     *
     * @return total frame count
     */
    public int totalFrames() {
        return (int) Math.round(secondsPerRotation * rotations * fps);
    }

    /**
     * Calculates total duration in seconds.
     *
     * @return duration in seconds
     */
    public double durationSeconds() {
        return frameTimeSeconds(totalFrames());
    }

    /**
     * Calculates the timestamp in seconds for a specific frame index.
     *
     * @param frame the zero-based frame index
     * @return timestamp in seconds
     */
    public double frameTimeSeconds(int frame) {
        return format == Format.GIF ? Math.round(frame * 100.0 / fps) / 100.0 : (double) frame / fps;
    }

    /**
     * Calculates the rotation yaw angle at a given frame.
     * <p>
     * The endpoint is deliberately omitted: the next frame after the last is the first again.
     *
     * @param frame the zero-based frame index
     * @return yaw angle in degrees
     * @throws IllegalArgumentException if the frame index is out of bounds
     */
    public float yawAtFrame(int frame) {
        if (frame < 0 || frame >= totalFrames()) {
            throw new IllegalArgumentException("Frame is outside this export.");
        }
        return (float) (direction.sign() * 360.0 * rotations * frameTimeSeconds(frame) / durationSeconds());
    }

    /**
     * Parses command-line arguments and flags to produce a validated {@link ExportOptions}.
     *
     * @param arguments array of argument strings and flags
     * @param moleculeCount total count of molecules in the export
     * @return a new validated {@link ExportOptions}
     * @throws IllegalArgumentException if parsing fails or option rules are violated
     */
    public static ExportOptions parse(String[] arguments, int moleculeCount) {
        if (moleculeCount < 1) {
            throw invalid("No molecules are loaded.");
        }
        if (moleculeCount > MAX_MOLECULES) {
            throw invalid("A gallery supports at most " + MAX_MOLECULES
                    + " molecules; export individual molecule IDs from this registry.");
        }
        Map<String, String> flags = flags(arguments);
        Format format = enumeration(flags, "format", Format.GIF, Format.class, "gif or mp4");
        int columns = moleculeCount == 1 ? 1 : integer(flags, "columns", 4, 1, 16);
        if (moleculeCount == 1 && flags.containsKey("columns")) {
            integer(flags, "columns", 1, 1, 16);
        }
        columns = Math.min(columns, moleculeCount);
        int rows = (moleculeCount + columns - 1) / columns;
        int cell = 300;
        if (moleculeCount > 1) {
            cell = Math.min(cell, 4096 / Math.max(columns, rows));
            cell = Math.min(cell, (int) Math.sqrt((double) MAX_PIXELS / (columns * rows)));
        }
        int defaultWidth = moleculeCount == 1 ? 600 : columns * cell;
        int defaultHeight = moleculeCount == 1 ? 600 : rows * cell;
        if (format == Format.MP4) {
            defaultWidth &= ~1;
            defaultHeight &= ~1;
        }
        int width = integer(flags, "width", defaultWidth, 64, 4096);
        int height = integer(flags, "height", defaultHeight, 64, 4096);
        if ((long) width * height > MAX_PIXELS) {
            throw invalid("The canvas may contain at most 8,388,608 pixels.");
        }
        if (moleculeCount > 1 && (width / columns < 96 || height / rows < 96)) {
            throw invalid("Each gallery cell needs at least 96 × 96 pixels. Adjust --columns, --width or --height.");
        }
        if (format == Format.MP4 && ((width & 1) != 0 || (height & 1) != 0)) {
            throw invalid("MP4 requires even --width and --height values.");
        }
        int fps = integer(flags, "fps", 20, 1, format == Format.MP4 ? Integer.MAX_VALUE : 60);
        if (format == Format.GIF) {
            fps = Math.min(fps, 50);
        }
        double seconds = decimal(flags, "speed", Config.SPIN_SECONDS.get(), 0.1, 600);
        int rotations = integer(flags, "rotations", 1, 1, 100);
        double duration = seconds * rotations;
        long frames = Math.round(duration * fps);
        if (duration > 600) {
            throw invalid("An export may last at most 600 seconds.");
        }
        if (frames < 2L * rotations) {
            throw invalid("Each rotation needs at least two frames. Use a slower --speed or higher --fps.");
        }
        if (frames > MAX_FRAMES) {
            throw invalid(
                    "An export may contain at most 7,200 frames. Reduce --fps, --speed (seconds per turn), or --rotations.");
        }
        Integer background = background(flags.getOrDefault("background", "#DED8C4"));
        if (format == Format.MP4 && background == null) {
            throw invalid("H.264 MP4 cannot preserve transparency. Set a matte with --background \"#RRGGBB\".");
        }
        Config.RotationDirection direction = enumeration(flags, "direction", Config.ROTATION_DIRECTION.get(),
                Config.RotationDirection.class, "forward or reverse");
        boolean cubic = Config.CUBIC_ATOMS.get();
        if (flags.containsKey("appearance")) {
            cubic = switch (flags.get("appearance").toLowerCase(Locale.ROOT)) {
                case "sphere" -> false;
                case "cube" -> true;
                default -> throw invalid("--appearance must be sphere or cube.");
            };
        }
        String filename = filename(flags.get("output"), format);
        String encoder = flags.get("encoder");
        if (encoder != null && encoder.isBlank()) {
            throw invalid("--encoder needs an FFmpeg executable path.");
        }
        if (encoder != null && format != Format.MP4) {
            throw invalid("--encoder is only used for MP4 exports.");
        }
        return new ExportOptions(format, width, height, fps, seconds, rotations, bool(flags, "name", true),
                bool(flags, "formula", true), background, direction, cubic, columns, filename, encoder, moleculeCount,
                decimal(flags, "text-scale", Config.TEXT_SCALE.get(), .5, 3.0));
    }

    /**
     * Parses command-line argument strings into a map of flags.
     *
     * @param arguments array of argument strings
     * @return map of flag names to values
     */
    private static Map<String, String> flags(String[] arguments) {
        return flags(arguments, Set.of());
    }

    /**
     * Parses command-line argument strings into a map of flags with additional allowed option names.
     *
     * @param arguments array of argument strings
     * @param additional set of additional allowed flag names
     * @return map of flag names to values
     */
    static Map<String, String> flags(String[] arguments, Set<String> additional) {
        List<String> tokens = quotedArguments(arguments);
        Map<String, String> result = new HashMap<>();
        for (int index = 0; index < tokens.size(); index += 2) {
            String flag = tokens.get(index);
            if (!flag.startsWith("--")) {
                throw invalid("Expected an option such as --format; found " + flag + ".");
            }
            String name = flag.substring(2).toLowerCase(Locale.ROOT);
            if (name.equals("seconds-per-rotation")) {
                name = "speed";
            }
            if (!FLAGS.contains(name) && !additional.contains(name)) {
                throw invalid("Unknown export option " + flag + ". Use /avogadro export help.");
            }
            if (index + 1 == tokens.size() || tokens.get(index + 1).startsWith("--")) {
                throw invalid(flag + " requires a value.");
            }
            if (result.putIfAbsent(name, tokens.get(index + 1)) != null) {
                throw invalid(flag + " was supplied twice.");
            }
        }
        return result;
    }

    /** Minecraft 1.12 splits commands on spaces before dispatch, including inside quotes. */
    private static List<String> quotedArguments(String[] arguments) {
        List<String> result = new ArrayList<>(arguments.length);
        StringBuilder quoted = null;
        for (String argument : arguments) {
            if (quoted == null && argument.isEmpty()) {
                continue;
            }
            if (quoted == null && !argument.startsWith("\"")) {
                if (argument.indexOf('"') >= 0) {
                    throw invalid("Quotes must surround an entire option value.");
                }
                result.add(argument);
                continue;
            }
            if (quoted == null) {
                quoted = new StringBuilder(argument.substring(1));
            } else {
                quoted.append(' ').append(argument);
            }
            if (!quoted.isEmpty() && quoted.charAt(quoted.length() - 1) == '"') {
                quoted.setLength(quoted.length() - 1);
                if (quoted.indexOf("\"") >= 0) {
                    throw invalid("Option values cannot contain embedded quotes.");
                }
                result.add(quoted.toString());
                quoted = null;
            }
        }
        if (quoted != null) {
            throw invalid("Close the quote around the option value.");
        }
        return result;
    }

    /** Parses and validates an integer flag value within specified bounds. */
    private static int integer(Map<String, String> flags, String name, int fallback, int minimum, int maximum) {
        String value = flags.get(name);
        if (value == null) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed >= minimum && parsed <= maximum) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
        }
        throw invalid("--" + name + " must be a whole number from " + minimum + " to " + maximum + ".");
    }

    /** Parses and validates a decimal flag value within specified bounds. */
    private static double decimal(Map<String, String> flags, String name, double fallback, double minimum,
            double maximum) {
        String value = flags.get(name);
        if (value == null) {
            return fallback;
        }
        try {
            double parsed = Double.parseDouble(value);
            if (Double.isFinite(parsed) && parsed >= minimum && parsed <= maximum) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
        }
        throw invalid("--" + name + " must be a finite number from " + minimum + " to " + maximum
                + (name.equals("speed") ? " seconds per rotation." : "."));
    }

    /** Parses and validates a boolean flag value. */
    private static boolean bool(Map<String, String> flags, String name, boolean fallback) {
        String value = flags.get(name);
        if (value == null) {
            return fallback;
        }
        if (value.equalsIgnoreCase("true")) {
            return true;
        }
        if (value.equalsIgnoreCase("false")) {
            return false;
        }
        throw invalid("--" + name + " must be true or false.");
    }

    /** Parses and validates an enumeration flag value. */
    private static <E extends Enum<E>> E enumeration(Map<String, String> flags, String name, E fallback, Class<E> type,
            String choices) {
        String value = flags.get(name);
        if (value == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            throw invalid("--" + name + " must be " + choices + ".");
        }
    }

    /** Parses and validates a background color string into an RGB integer or null. */
    private static Integer background(String value) {
        if (value.equalsIgnoreCase("none") || value.equalsIgnoreCase("transparent")) {
            return null;
        }
        if (!value.matches("#[0-9a-fA-F]{6}")) {
            throw invalid("--background must be \"#RRGGBB\" or none.");
        }
        return Integer.parseInt(value.substring(1), 16);
    }

    /** Validates and formats the output filename for the export format. */
    private static String filename(String value, Format format) {
        if (value == null) {
            return null;
        }
        if (value.isBlank() || value.length() > 128 || value.equals(".") || value.equals("..")
                || value.matches(".*[<>:\"/\\\\|?*\\p{Cntrl}].*") || value.endsWith(".") || value.endsWith(" ")
                || value.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?")) {
            throw invalid("--output must be a filename, without directories or reserved filename characters.");
        }
        int dot = value.lastIndexOf('.');
        if (dot < 0) {
            return value + "." + format.extension;
        }
        if (!value.substring(dot + 1).equalsIgnoreCase(format.extension)) {
            throw invalid("--output must end in ." + format.extension + " for this format.");
        }
        return value;
    }

    /** Helper to create an invalid argument exception. */
    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}

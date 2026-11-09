package io.github.wurtzitane.avogadro.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;


/** Extracts the downloadable creator from the client JAR and starts it with the current instance's themes. */
public final class ThemeCreatorLauncher {
    private static Process process;

    private ThemeCreatorLauncher() {}

    /**
     * Opens the standalone editor with the bundled light theme. Repeated clicks do not start duplicate editors.
     *
     * @throws IOException if the bundled tool cannot be extracted or Java cannot be started
     */
    public static void open() throws IOException {
        if (process != null && process.isAlive()) return;
        Path themes = UiThemes.directory();
        if (themes == null) throw new IOException("The client theme folder is not initialized");
        Path folder = themes.getParent().resolve("theme-creator");
        Files.createDirectories(folder);
        Path application = folder.resolve("avogadro-theme-creator.jar");
        try (var stream = ThemeCreatorLauncher.class.getResourceAsStream("/assets/avogadro/tools/theme-creator.jar")) {
            if (stream == null) throw new IOException("The Avogadro download is missing Theme Creator");
            byte[] bytes = stream.readAllBytes();
            if (!Files.exists(application) || !Arrays.equals(bytes, Files.readAllBytes(application)))
                Files.write(application, bytes);
        }
        boolean windows = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
        Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "javaw.exe" : "java");
        process = new ProcessBuilder(java.toString(), "-jar", application.toString(),
                "--themes-directory", themes.toString())
                .directory(folder.toFile()).redirectErrorStream(true)
                .redirectOutput(folder.resolve("creator.log").toFile()).start();
    }
}

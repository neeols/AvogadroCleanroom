package io.github.wurtzitane.avogadro.themecreator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import io.github.wurtzitane.avogadro.theme.ThemeDefinition;

/** Theme-file writes never expose a partly written document or change the editor's saved state on failure. */
final class ThemeFiles {
    private ThemeFiles() {}

    /**
     * Writes UTF-8 to a temporary sibling and replaces the destination only after serialization succeeds. Atomic
     * replacement is preferred; unsupported filesystems use a normal replacement. Temporary files are cleaned on
     * failure.
     */
    static void write(Path destination, ThemeDefinition document) throws IOException {
        Path file = destination.toAbsolutePath().normalize(), parent = file.getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".avogadro-theme-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                document.write(writer);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Accept common picker/clipboard spellings; exported JSON still uses the strict shared hex format. */
    static int color(String value) {
        String text = value.trim();
        if (text.startsWith("0x") || text.startsWith("0X"))
            text = text.substring(2);
        if (!text.startsWith("#"))
            text = "#" + text;
        if (text.length() == 4 || text.length() == 5) {
            StringBuilder expanded = new StringBuilder("#");
            for (int i = 1; i < text.length(); i++)
                expanded.append(text.charAt(i)).append(text.charAt(i));
            text = expanded.toString();
        }
        return ThemeDefinition.hex(text);
    }
}

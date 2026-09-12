package dev.claudev.platform.windows.detect;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Lists immediate subdirectories of a root matching a glob pattern (e.g. {@code "erl-*"}). */
public final class GlobDirectoryScanner {

    private static final Logger LOG = Logger.getLogger(GlobDirectoryScanner.class.getName());

    public List<Path> matchingSubdirectories(Path root, String glob) {
        List<Path> matches = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return matches;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root, glob)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    matches.add(entry);
                }
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, "Glob scan failed for " + root + " (" + glob + ")", e);
        }
        return matches;
    }
}

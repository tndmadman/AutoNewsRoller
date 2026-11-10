package autonewsroller.safety;

import autonewsroller.config.NewsConfig;
import java.io.IOException;
import java.nio.file.*;
import java.util.Locale;

/**
 * Resolve the dedicated content-safety interpreter identically for the
 * GPU worker and controller health endpoint. The system PATH is not a
 * reliable indicator of where pinned scanner dependencies were installed.
 */
public final class SafetyPython {
    private SafetyPython() {}

    public static String resolve(Path root, NewsConfig cfg) throws IOException {
        String configured = cfg.get("contentSafetyPython", "auto");
        Path project = root.toAbsolutePath().normalize();
        if (configured.isBlank() || configured.equalsIgnoreCase("auto")
                || configured.equalsIgnoreCase("python")
                || configured.equalsIgnoreCase("python3")) {
            // "python" and "python3" are legacy defaults; silently invoking
            // them used to select an unrelated interpreter on worker machines.
            boolean windows = System.getProperty("os.name", "")
                    .toLowerCase(Locale.ROOT).contains("win");
            Path interpreter = project.resolve(windows
                    ? Path.of(".venv-safety", "Scripts", "python.exe")
                    : Path.of(".venv-safety", "bin", "python"));
            if (Files.isRegularFile(interpreter)) {
                return interpreter.toString();
            }
            throw new IOException("Dedicated content-safety Python is missing: " + interpreter
                    + ". On Windows run setup_content_safety_windows.bat "
                    + "from the AutoNewsRoller directory, then restart the worker. "
                    + "On Linux create .venv-safety with Python 3.12 and "
                    + "install -r requirements-content-safety.txt.");
        }
        // Custom interpreters must be explicit executable paths; never
        // run them through a shell or accept shell arguments.
        Path interpreter = Path.of(configured);
        if (!interpreter.isAbsolute()) interpreter = project.resolve(interpreter);
        interpreter = interpreter.toAbsolutePath().normalize();
        if (!Files.isRegularFile(interpreter))
            throw new IOException("Configured contentSafetyPython interpreter not found: "
                    + interpreter + ". Run setup_content_safety_windows.bat "
                    + "or specify an existing interpreter file.");
        return interpreter.toString();
    }
}

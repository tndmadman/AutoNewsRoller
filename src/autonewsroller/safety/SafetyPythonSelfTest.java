package autonewsroller.safety;

import autonewsroller.config.NewsConfig;
import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;
import java.util.Locale;

/** Regression: the worker and controller must never silently pick PATH Python. */
public final class SafetyPythonSelfTest {
    private SafetyPythonSelfTest() {}
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("anr-safety-python-");
        try {
            NewsConfig cfg = NewsConfig.load(root);
            boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
            Path dedicated = root.resolve(windows
                    ? Path.of(".venv-safety", "Scripts", "python.exe")
                    : Path.of(".venv-safety", "bin", "python"));
            missing(() -> SafetyPython.resolve(root, cfg));
            Files.createDirectories(dedicated.getParent());
            Files.writeString(dedicated, "test interpreter placeholder");
            if (!SafetyPython.resolve(root, cfg).equals(dedicated.toString()))
                throw new AssertionError("Auto-selection ignored dedicated venv");
            cfg.set("contentSafetyPython", "python"); // legacy configuration must be safe
            if (!SafetyPython.resolve(root, cfg).equals(dedicated.toString()))
                throw new AssertionError("Legacy python alias used PATH");
            cfg.set("contentSafetyPython", "python3");
            if (!SafetyPython.resolve(root, cfg).equals(dedicated.toString()))
                throw new AssertionError("Legacy python3 alias used PATH");
            cfg.set("contentSafetyPython", "custom/missing-python.exe");
            missing(() -> SafetyPython.resolve(root, cfg));
            Path custom = root.resolve("custom/python.exe");
            Files.createDirectories(custom.getParent());
            Files.writeString(custom, "test custom interpreter");
            cfg.set("contentSafetyPython", "custom/python.exe");
            if (!SafetyPython.resolve(root, cfg).equals(custom.toString()))
                throw new AssertionError("Explicit custom interpreter ignored");
            System.out.println("SafetyPythonSelfTest PASS (venv selection, legacy aliases, missing/deferred setup, custom path)");
        } finally {
            try (var walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
            }
        }
    }
    private interface Operation { void run() throws Exception; }
    private static void missing(Operation action) throws Exception {
        try {
            action.run();
            throw new AssertionError("Missing interpreter was silently accepted");
        } catch (IOException expected) {
            if (!expected.getMessage().contains("setup_content_safety_windows.bat"))
                throw new AssertionError("Missing actionable setup instruction", expected);
        }
    }
}

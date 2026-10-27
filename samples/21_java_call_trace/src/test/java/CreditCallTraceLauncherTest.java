import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Checks the Python process boundary without calling or replacing an LLM. */
class CreditCallTraceLauncherTest {
    @TempDir
    Path directory;

    @Test
    void demonstrationLauncherOnlyRunsMavenAndPassesItsExitCode() throws Exception {
        Files.copy(Path.of("main.py"), directory.resolve("main.py"));
        Path arguments = directory.resolve("arguments.txt");
        Path executable = directory.resolve("mvn");
        Files.writeString(executable, "#!/bin/sh\nprintf '%s\\n' \"$@\" > \"$CAPTURE_ARGUMENTS\"\nexit \"$MAVEN_EXIT\"\n");
        executable.toFile().setExecutable(true);

        assertEquals(0, launch("main.py", executable, arguments, "0"),
                "A successful demonstration must not require acceptance logs");
        assertEquals(List.of("-q", "--batch-mode", "compile", "exec:java"), Files.readAllLines(arguments));
        assertEquals(7, launch("main.py", executable, arguments, "7"), "The Maven exit code must reach the caller");
    }

    @Test
    void acceptanceLauncherEnablesTheRealModelJUnitProfile() throws Exception {
        Files.copy(Path.of("test.py"), directory.resolve("test.py"));
        Path arguments = directory.resolve("arguments.txt");
        Path executable = directory.resolve("mvn");
        Files.writeString(executable, "#!/bin/sh\nprintf '%s\\n' \"$@\" > \"$CAPTURE_ARGUMENTS\"\nexit \"$MAVEN_EXIT\"\n");
        executable.toFile().setExecutable(true);

        assertEquals(0, launch("test.py", executable, arguments, "0"));
        assertEquals(List.of("-q", "--batch-mode", "-Preal-model", "test"), Files.readAllLines(arguments));
        assertEquals(9, launch("test.py", executable, arguments, "9"));
    }

    private int launch(String script, Path executable, Path arguments, String exitCode) throws Exception {
        ProcessBuilder process = new ProcessBuilder("python3", directory.resolve(script).toString());
        process.environment().put("MAVEN_CMD", executable.toString());
        process.environment().put("CAPTURE_ARGUMENTS", arguments.toString());
        process.environment().put("MAVEN_EXIT", exitCode);
        process.redirectErrorStream(true);
        Process child = process.start();
        String output = new String(child.getInputStream().readAllBytes());
        int result = child.waitFor();
        if (result != Integer.parseInt(exitCode)) {
            System.out.println(output);
        }
        return result;
    }
}

package com.network.monitoring.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class PythonProcessRunnerTest {
    @TempDir Path directory;
    // Match production's stable workspace cwd; only output artifacts use temporary directories.
    private final Path workingDirectory = Path.of(System.getProperty("user.dir"));

    private List<String> command(String mode) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        String classes = Path.of(Child.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        return List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-cp", classes, Child.class.getName(), mode, directory.resolve("child.pid").toString());
    }

    @Test
    void largeOutputCannotBlockDeadlineAndReturnedDiagnosticsAreBounded() throws Exception {
        var output = PythonProcessRunner.execute(command("output"), workingDirectory, 10);
        assertEquals(0, output.exitCode());
        assertTrue(output.text().endsWith("[Process output truncated]"));
        assertTrue(output.text().length() < 66000);
    }

    @Test
    void timeoutTerminatesTheProcessAndReleasesTheWorker() throws Exception {
        var ex = assertThrows(IOException.class, () -> PythonProcessRunner.execute(command("sleep"), workingDirectory, 2));
        assertTrue(ex.getMessage().contains("exceeded"));
        long pid = Long.parseLong(Files.readString(directory.resolve("child.pid")));
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }

    @Test
    void interruptionTerminatesChildAndPreservesInterruptFlag() throws Exception {
        var interrupted = new AtomicBoolean();
        List<String> command = command("sleep");
        var worker = new Thread(() -> {
            try { PythonProcessRunner.execute(command, workingDirectory, 20); }
            catch (IOException ex) { interrupted.set(Thread.currentThread().isInterrupted()); }
        });
        worker.start();
        try {
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (!Files.exists(directory.resolve("child.pid")) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(directory.resolve("child.pid")));
            worker.interrupt();
            worker.join(7000);
            assertFalse(worker.isAlive());
            assertTrue(interrupted.get());
            long pid = Long.parseLong(Files.readString(directory.resolve("child.pid")));
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        } finally {
            worker.interrupt();
            worker.join(7000);
        }
    }

    public static class Child {
        public static void main(String[] args) throws Exception {
            Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
            if ("sleep".equals(args[0])) Thread.sleep(60_000);
            else System.out.print("x".repeat(200_000));
        }
    }
}

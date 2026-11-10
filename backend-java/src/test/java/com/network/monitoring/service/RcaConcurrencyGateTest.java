package com.network.monitoring.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class RcaConcurrencyGateTest {
    @TempDir Path directory;
    @Test void rejectsConcurrentWorkAndReleasesPermitAfterProcessFailure() throws Exception {
        var gate = new RcaConcurrencyGate(1, 0);
        Path cwd = Path.of(System.getProperty("user.dir"));
        assertThrows(IOException.class, () -> gate.execute(List.of(directory.resolve("missing-executable").toString()), cwd, 1));
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        String classes = Path.of(PythonProcessRunnerTest.Child.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        List<String> command = List.of(java, "-cp", classes, PythonProcessRunnerTest.Child.class.getName(), "sleep", directory.resolve("child.pid").toString());
        Thread worker = new Thread(() -> { try { gate.execute(command, cwd, 20); } catch (IOException ignored) { } });
        worker.start();
        try {
            long end = System.nanoTime() + 5_000_000_000L;
            while (!Files.exists(directory.resolve("child.pid")) && System.nanoTime() < end) Thread.sleep(10);
            assertTrue(Files.exists(directory.resolve("child.pid")));
            var busy = assertThrows(IOException.class, () -> gate.execute(List.of(java, "-version"), cwd, 3));
            assertTrue(busy.getMessage().contains("busy"));
        } finally { worker.interrupt(); worker.join(7000); }
        assertFalse(worker.isAlive()); assertEquals(0, gate.execute(List.of(java, "-version"), cwd, 3).exitCode());
    }
}

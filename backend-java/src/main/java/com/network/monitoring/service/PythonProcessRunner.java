package com.network.monitoring.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Redirect output to disk so a full output pipe cannot defeat the execution deadline. */
final class PythonProcessRunner {
    record Output(int exitCode, String text) { }

    static Output execute(List<String> command, Path directory, long timeoutSeconds) throws IOException {
        if (timeoutSeconds < 1) throw new IllegalArgumentException("RCA timeout must be positive.");
        Path output = Files.createTempFile("rca-process-", ".log");
        Process process = null;
        try {
            process = new ProcessBuilder(command).directory(directory.toFile())
                    .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                terminate(process);
                throw new IOException("RCA Python execution exceeded " + timeoutSeconds + " seconds. Please reduce the input or review server capacity.");
            }
            try (var input = Files.newInputStream(output)) {
                byte[] bytes = input.readNBytes(64 * 1024);
                String text = new String(bytes, StandardCharsets.UTF_8);
                if (input.read() != -1) text += "\n[Process output truncated]";
                return new Output(process.exitValue(), text.trim());
            }
        } catch (InterruptedException ex) {
            if (process != null) terminate(process);
            Thread.currentThread().interrupt();
            throw new IOException("RCA Python execution interrupted.", ex);
        } finally {
            if (process != null && process.isAlive()) terminate(process);
            if (process != null) {
                try { process.getInputStream().close(); } catch (IOException ignored) { }
                try { process.getErrorStream().close(); } catch (IOException ignored) { }
                try { process.getOutputStream().close(); } catch (IOException ignored) { }
            }
            try { Files.deleteIfExists(output); } catch (IOException ignored) { }
        }
    }

    private static void terminate(Process process) {
        process.descendants().forEach(handle -> handle.destroyForcibly());
        process.destroyForcibly();
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}

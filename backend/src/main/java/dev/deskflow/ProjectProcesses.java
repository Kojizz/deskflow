package dev.deskflow;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

final class ProjectProcesses {
    static String run(Path cwd, List<String> argv, int seconds, BooleanSupplier cancelled) {
        Process process = null;
        java.util.concurrent.atomic.AtomicBoolean overflow = new java.util.concurrent.atomic.AtomicBoolean();
        Set<ProcessHandle> children = ConcurrentHashMap.newKeySet();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            ProcessBuilder builder = new ProcessBuilder(argv);
            builder.directory(cwd.toFile());
            builder.redirectErrorStream(true);
            String systemRoot = System.getenv("SystemRoot");
            builder.environment().clear();
            if (systemRoot != null) {
                builder.environment().put("SystemRoot", systemRoot);
            }
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
            builder.environment().put("GIT_CONFIG_GLOBAL", System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null");
            process = builder.start();
            process.getOutputStream().close();
            Process running = process;
            Thread reader = Thread.ofVirtual().start(() -> {
                try (InputStream input = running.getInputStream()) {
                    byte[] bytes = new byte[2048];
                    int count;
                    while ((count = input.read(bytes)) != -1) {
                        synchronized (output) {
                            if (output.size() + count > 65536) {
                                overflow.set(true);
                                running.descendants().forEach(children::add);
                                running.destroyForcibly();
                                return;
                            }
                            output.write(bytes, 0, count);
                        }
                    }
                } catch (IOException ignored) {
                    // A terminated process closes the pipe.
                }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            while (true) {
                process.descendants().forEach(children::add);
                if (cancelled.getAsBoolean()) {
                    throw new Actions.Failure("Project command cancelled", false);
                }
                if (System.nanoTime() > deadline) {
                    throw new Actions.Failure("Project command timed out; inspect before resume", false);
                }
                if (process.waitFor(25, TimeUnit.MILLISECONDS)) {
                    break;
                }
            }
            reader.join(1000);
            if (reader.isAlive() || children.stream().anyMatch(ProcessHandle::isAlive)) {
                throw new Actions.Failure("Background processes are forbidden", false);
            }
            if (overflow.get()) {
                throw new Actions.Failure("Command output exceeds 64 KB", false);
            }
            if (process.exitValue() != 0) {
                throw new Actions.Failure("Project command failed; inspect before resume", false);
            }
            synchronized (output) {
                return output.toString(StandardCharsets.UTF_8);
            }
        } catch (Actions.Failure error) {
            throw error;
        } catch (Exception error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new Actions.Failure("Project process failed; inspect before resume", false);
        } finally {
            if (process != null) {
                process.descendants().forEach(children::add);
                children.forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                children.forEach(child -> {
                    try {
                        child.onExit().get(2, TimeUnit.SECONDS);
                    } catch (Exception ignored) {
                        // The instance remains fenced when a command fails.
                    }
                });
            }
        }
    }
}

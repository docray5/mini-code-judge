package com.bilicki.codejudge.worker.sandbox;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@RequiredArgsConstructor
public class SandboxSessionImpl implements SandboxSession {
    private final DockerClient dockerClient;
    private final String containerId;
    private final SandboxProperties properties;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private record ExecResult(
            long exitCode,
            String stdout,
            String stderr,
            boolean timedOut,
            boolean outputLimitExceeded,
            boolean oomKilled,
            long durationMs
    ) { }

    @Override
    public CompileResult compile(String javaSourceCode) {
        String base64Code = Base64.getEncoder().encodeToString(javaSourceCode.getBytes(StandardCharsets.UTF_8));

        int compileTimeoutSeconds = (int) properties.compileTimeout().toSeconds();

        String compileCodeCommand = String.format("echo \"$CODE_B64\" | base64 -d > /tmp/Main.java && timeout -k 1s %ds javac -d /tmp /tmp/Main.java", compileTimeoutSeconds);

        ExecResult result = executeCmd(compileCodeCommand, List.of("CODE_B64="+base64Code), compileTimeoutSeconds);

        // TODO remove this later (it needs to be logged in the class calling this method)
        if (result.exitCode() != 0) {
            log.error("Compilation error:\n{}", result.stderr());
        }

        return new CompileResult(!result.timedOut && result.exitCode == 0, result.timedOut, result.stderr);
    }

    @Override
    public RunTestResult runTest(String stdin, int runTestTimeoutSeconds) {
        String inputB64 = Base64.getEncoder().encodeToString(stdin.getBytes());

        String runCodeCommand = String.format(Locale.US, "echo \"$STDIN_B64\" | base64 -d | timeout -k 1s %ds java -XX:MaxRAMPercentage=%.1f -XX:+UseSerialGC -XX:-UsePerfData -cp /tmp Main", runTestTimeoutSeconds, properties.heapPercentage());

        ExecResult result = executeCmd(runCodeCommand, List.of("STDIN_B64=" + inputB64), runTestTimeoutSeconds);

        return new RunTestResult(
                result.exitCode(), result.stdout, result.stderr,
                result.timedOut, result.outputLimitExceeded, result.oomKilled, result.durationMs
        );
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        try {
            dockerClient.removeContainerCmd(containerId)
                    .withForce(true)
                    .withRemoveVolumes(true)
                    .exec();
        } catch (Exception e) {
            log.warn("Failed to remove container {}: {}", containerId, e.getMessage());
        }
    }

    /** @return true when bytes were written properly within limit, otherwise false */
    private boolean writeWithBound(ByteArrayOutputStream stream, byte[] value) {
        int remaining = properties.maxOutputBytes() - stream.size();
        if (value.length > remaining) {
            if (remaining > 0) {
                stream.write(value, 0, remaining);
            }
            return false;
        }
        stream.write(value, 0, value.length);
        return true;
    }

    private ExecResult executeCmd(String command, List<String> env, int timeoutSeconds) {
        ExecCreateCmdResponse exec = dockerClient.execCreateCmd(containerId)
                .withAttachStderr(true)
                .withAttachStdout(true)
                .withEnv(env)
                .withCmd("/bin/sh", "-c", command)
                .exec();

        ByteArrayOutputStream stdoutOutputStream = new ByteArrayOutputStream();
        ByteArrayOutputStream stderrOutputStream = new ByteArrayOutputStream();

        AtomicBoolean outputExceeded = new AtomicBoolean(false);
        long startNanos = System.nanoTime();
        boolean completedInTime = false;

        try (ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
            @Override
            public void onNext(Frame frame) {
                byte[] payload = frame.getPayload();
                if (payload == null) return;

                if (frame.getStreamType() == StreamType.STDOUT) {
                    if (!writeWithBound(stdoutOutputStream, payload)) {
                        outputExceeded.set(true);
                    }
                } else if (frame.getStreamType() == StreamType.STDERR) {
                    writeWithBound(stderrOutputStream, payload);
                }
            }
        }) {
            completedInTime = dockerClient.execStartCmd(exec.getId())
                    .exec(callback)
                    .awaitCompletion(timeoutSeconds + 2L, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Stream closed early or failed: {}", e.getMessage());
        }

        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        Long exitCodeObj = dockerClient.inspectExecCmd(exec.getId()).exec().getExitCodeLong();
        long exitCode = (exitCodeObj != null) ? exitCodeObj : -1L;

        InspectContainerResponse.ContainerState state = dockerClient.inspectContainerCmd(containerId).exec().getState();
        boolean oomKilled = Boolean.TRUE.equals(state.getOOMKilled());

        String stderrOutputString = stderrOutputStream.toString();

        boolean timedOut = !completedInTime || exitCode == 124;
        boolean oom = !timedOut && (oomKilled
                || exitCode == 137
                || stderrOutputString.contains("java.lang.OutOfMemoryError"));

        return new ExecResult(
                exitCode, stdoutOutputStream.toString(), stderrOutputString,
                timedOut, outputExceeded.get(), oom, durationMs
        );
    }
}

package com.bilicki.codejudge.worker.sandbox;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@RequiredArgsConstructor
public class SandboxSessionImpl implements SandboxSession {
    private final DockerClient dockerClient;
    private final String containerId;
    private final SandboxProperties properties;

    private record ExecResult(
            long exitCode,
            String stdout,
            String stderr
    ) { }

    @Override
    public CompileResult compile(String javaSourceCode) {
        String base64Code = Base64.getEncoder().encodeToString(javaSourceCode.getBytes(StandardCharsets.UTF_8));

        String compileCodeCommand = "echo \"$CODE_B64\" | base64 -d > /tmp/Main.java && javac -d /tmp /tmp/Main.java";

        ExecResult compileExecResult = executeCmd(compileCodeCommand, List.of("CODE_B64="+base64Code), (int) properties.compileTimeout().toSeconds());

        if (compileExecResult.exitCode() != 0) {
            log.error("Compilation error:\n{}", compileExecResult.stderr());
        }

        return new CompileResult();
    }

    @Override
    public RunTestResult runTest(String stdin, int runTestTimeoutSeconds) {
        String inputB64 = Base64.getEncoder().encodeToString(stdin.getBytes());

        String runCodeCommand = String.format("echo \"%s\" | base64 -d | java -XX:MaxRAMPercentage=%.1f -XX:+UseSerialGC -XX:-UsePerfData -cp /tmp Main", inputB64, properties.heapPercentage());

        ExecResult runExecResult = executeCmd(runCodeCommand, List.of(), runTestTimeoutSeconds);

        if (runExecResult.exitCode() != 0) {
            System.out.println("Runtime error:\n" + runExecResult.stderr());
        } else {
            System.out.println("Output:\n" + runExecResult.stdout());
        }

        return new RunTestResult();
    }

    @Override
    public void close() throws Exception {
        dockerClient.removeContainerCmd(containerId)
                .withForce(true)
                .withRemoveVolumes(true)
                .exec();
    }

    private void writeWithBound(ByteArrayOutputStream stream, byte[] value) {
        if (stream.size() < properties.maxOutputBytes()) {
            stream.write(value, 0, Math.min(value.length, properties.maxOutputBytes() - stream.size()));
        }
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

        try {
            dockerClient.execStartCmd(exec.getId())
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            try {
                                byte[] payload = frame.getPayload();
                                if (payload == null) return;

                                if (frame.getStreamType() == StreamType.STDOUT) {
                                    writeWithBound(stdoutOutputStream, payload);
                                } else if (frame.getStreamType() == StreamType.STDERR) {
                                    writeWithBound(stderrOutputStream, payload);
                                }
                            } catch (Exception ignored) {}
                        }
                    })
                    .awaitCompletion(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        Long exitCodeObj = dockerClient.inspectExecCmd(exec.getId()).exec().getExitCodeLong();
        long exitCode = (exitCodeObj != null) ? exitCodeObj : -1L;

        return new ExecResult(exitCode, stdoutOutputStream.toString(), stderrOutputStream.toString());
    }
}

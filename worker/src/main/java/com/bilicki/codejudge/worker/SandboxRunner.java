package com.bilicki.codejudge.worker;

import com.bilicki.codejudge.worker.sandbox.SandboxProperties;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@RequiredArgsConstructor
@Component
public class SandboxRunner {

    private final DockerClient dockerClient;
    private final SandboxProperties properties;

    public void executeJavaCode(String javaSourceCode, int runTestTimeoutSeconds) {
        // setup and start container
        String base64Code = Base64.getEncoder().encodeToString(javaSourceCode.getBytes(StandardCharsets.UTF_8));

        long memoryBytes = properties.memoryMb() * 1024 * 1024;
        long nanoCpus = (long) (properties.cpus() * 1_000_000_000L);

        HostConfig hostConfig = HostConfig.newHostConfig()
                .withNanoCPUs(nanoCpus)
                .withMemory(memoryBytes)
                .withMemorySwap(memoryBytes)
                .withPidsLimit(properties.pidsLimit())
                .withUlimits(List.of(new Ulimit("nofile", properties.numberOfFilesLimit(), properties.numberOfFilesLimit())))
                .withNetworkMode("none")
                .withIpcMode("none")
                .withCapDrop(Capability.ALL)
                .withSecurityOpts(List.of("no-new-privileges:true"))
                .withReadonlyRootfs(true)
                .withTmpFs(Map.of("/tmp", "rw,noexec,nosuid,nodev,size=" + properties.tmpfsMb() + "m"));

        CreateContainerResponse containerResponse = dockerClient.createContainerCmd(properties.image())
                .withLabels(Map.of(properties.labelKey(), properties.labelValue()))
                .withUser(properties.user())
                .withHostConfig(hostConfig)
                .withEnv("CODE_B64=" + base64Code)
                .withCmd("/bin/sh", "-c", "sleep " + properties.containerLifetime().toSeconds())
                .exec();

        String containerId = containerResponse.getId();

        try {
            dockerClient.startContainerCmd(containerId).exec();

            // Compile the script

            String compileCodeCommand = "echo \"$CODE_B64\" | base64 -d > /tmp/Main.java && " +
                    "javac -d /tmp /tmp/Main.java";

            ExecCreateCmdResponse compileExec = dockerClient.execCreateCmd(containerId)
                    .withAttachStderr(true)
                    .withAttachStdout(true)
                    .withCmd("/bin/sh", "-c", compileCodeCommand)
                    .exec();

            ExecResult compileExecResult = runExecAndGetLogs(compileExec.getId(), (int) properties.compileTimeout().toSeconds());

            if (compileExecResult.exitCode() != 0) {
                log.error("Compilation error:\n{}", compileExecResult.stderr());
                return;
            }

            // Run the script

            String inputB64 = Base64.getEncoder().encodeToString("10\n".getBytes());

            String runCodeCommand = String.format("echo \"%s\" | base64 -d | java -XX:MaxRAMPercentage=%.1f -XX:+UseSerialGC -XX:-UsePerfData -cp /tmp Main", inputB64, properties.heapPercentage());

            ExecCreateCmdResponse execRunCode = dockerClient.execCreateCmd(containerId)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .withCmd("/bin/sh", "-c", runCodeCommand)
                    .exec();

            ExecResult runExecResult = runExecAndGetLogs(execRunCode.getId(), runTestTimeoutSeconds);

            if (runExecResult.exitCode() != 0) {
                System.out.println("Runtime error:\n" + runExecResult.stderr());
            } else {
                System.out.println("Output:\n" + runExecResult.stdout());
            }
        } finally {
            try {
                dockerClient.removeContainerCmd(containerId)
                        .withForce(true)
                        .withRemoveVolumes(true)
                        .exec();
            } catch (Exception ignored) {}
        }
    }

    private void writeWithBound(ByteArrayOutputStream stream, byte[] value) {
        if (stream.size() < properties.maxOutputBytes()) {
            stream.write(value, 0, Math.min(value.length, properties.maxOutputBytes() - stream.size()));
        }
    }

    private ExecResult runExecAndGetLogs(String execId, int timeoutSeconds) {
        ByteArrayOutputStream stdoutOutputStream = new ByteArrayOutputStream();
        ByteArrayOutputStream stderrOutputStream = new ByteArrayOutputStream();

        try {
            dockerClient.execStartCmd(execId)
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

        Long exitCodeObj = dockerClient.inspectExecCmd(execId).exec().getExitCodeLong();
        long exitCode = (exitCodeObj != null) ? exitCodeObj : -1L;

        return new ExecResult(exitCode, stdoutOutputStream.toString(), stderrOutputStream.toString());
    }
}

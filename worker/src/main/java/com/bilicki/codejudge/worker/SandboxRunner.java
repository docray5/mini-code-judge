package com.bilicki.codejudge.worker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
public class SandboxRunner {

    private final int MAX_OUTPUT_BYTES = 256 * 1024;

    private DockerClientConfig config;
    private DockerHttpClient httpClient;
    private DockerClient dockerClient;

    public SandboxRunner() {
        config = new DefaultDockerClientConfig.Builder()
                .withDockerHost("unix:///var/run/docker.sock")
                .build();
        httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .maxConnections(100)
                .connectionTimeout(Duration.ofSeconds(30))
                .responseTimeout(Duration.ofSeconds(30))
                .build();

        dockerClient = DockerClientImpl.getInstance(config, httpClient);
    }

    public void executeJavaCode(String javaSourceCode) {
        // setup and start container
        String base64Code = Base64.getEncoder().encodeToString(javaSourceCode.getBytes(StandardCharsets.UTF_8));

        long memoryBytes = 128 * 1024 * 1024;
        long nanoCpus = (long) (0.5f * 1_000_000_000L);

        // run this command essentially:
        // docker run -it --rm --user 65534:65534 --cpus 0.5 --memory=128m --memory-swap=128m --pids-limit=32 --network none --cap-drop=ALL --security-opt no-new-privileges:true --read-only --tmpfs /tmp:rw,noexec,nosuid,nodev,size=32m --ipc=none --ulimit nofile=64:64 --name java-sandbox-test java-sandbox:21 /bin/sh
        HostConfig hostConfig = HostConfig.newHostConfig()
                .withNanoCPUs(nanoCpus)
                .withMemory(memoryBytes)
                .withMemorySwap(memoryBytes)
                .withPidsLimit(32L)
                .withUlimits(List.of(new Ulimit("nofile", 64L, 64L)))
                .withNetworkMode("none")
                .withIpcMode("none")
                .withCapDrop(Capability.ALL)
                .withSecurityOpts(List.of("no-new-privileges:true"))
                .withReadonlyRootfs(true)
                .withTmpFs(Map.of("/tmp", "rw,noexec,nosuid,nodev,size=" + 32 + "m"));

        int containerTimeoutSeconds = 15;
        int compileTimeoutSeconds = 5;
        int runCodePerTestTimeoutSeconds = 3;

        CreateContainerResponse containerResponse = dockerClient.createContainerCmd("java-sandbox:21")
                .withUser("65534:65534")
                .withHostConfig(hostConfig)
                .withEnv("CODE_B64=" + base64Code)
                .withCmd("/bin/sh", "-c", "sleep " + containerTimeoutSeconds)
                .exec();

        String containerId = containerResponse.getId();

        dockerClient.startContainerCmd(containerId).exec();

        try {
            // Compile the script

            String compileCodeCommand = "echo \"$CODE_B64\" | base64 -d > /tmp/Main.java && " +
                    "javac -d /tmp /tmp/Main.java";

            ExecCreateCmdResponse compileExec = dockerClient.execCreateCmd(containerId)
                    .withAttachStderr(true)
                    .withAttachStdout(true)
                    .withCmd("/bin/sh", "-c", compileCodeCommand)
                    .exec();

            ExecResult compileExecResult = runExecAndGetLogs(compileExec.getId(), 5);

            if (!compileExecResult.stderr().isBlank() || compileExecResult.exitCode() != 0) {
                log.error("Compilation error:\n{}", compileExecResult.stderr());
                return;
            }

            // Run the script

            String inputB64 = Base64.getEncoder().encodeToString("10\n".getBytes());

            String runCodeCommand = String.format("echo \"%s\" | base64 -d | java -XX:MaxRAMPercentage=80.0 -XX:+UseSerialGC -XX:-UsePerfData -cp /tmp Main", inputB64);

            ExecCreateCmdResponse execRunCode = dockerClient.execCreateCmd(containerId)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .withCmd("/bin/sh", "-c", runCodeCommand)
                    .exec();

            ExecResult runExecResult = runExecAndGetLogs(execRunCode.getId(), 3);

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
        if (stream.size() < MAX_OUTPUT_BYTES) {
            stream.write(value, 0, Math.min(value.length, MAX_OUTPUT_BYTES - stream.size()));
        }
    }

    private ExecResult runExecAndGetLogs(String execId, int timeoutSeconds) {
        ByteArrayOutputStream stdoutOutputStream = new ByteArrayOutputStream(MAX_OUTPUT_BYTES);
        ByteArrayOutputStream stderrOutputStream = new ByteArrayOutputStream(MAX_OUTPUT_BYTES);

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

        long exitCode = dockerClient.inspectExecCmd(execId).exec().getExitCodeLong();

        return new ExecResult(exitCode, stdoutOutputStream.toString(), stderrOutputStream.toString());
    }
}

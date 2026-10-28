package com.bilicki.codejudge.worker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ulimit;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class SandboxRunner {

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

        String className = getClassNameFromSourceCode(javaSourceCode);

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

            String compileCodeCommand = String.format("echo \"$CODE_B64\" | base64 -d > /tmp/%s.java && " +
                    "javac -d /tmp /tmp/%s.java", className, className);

            ExecCreateCmdResponse compileExec = dockerClient.execCreateCmd(containerId)
                    .withAttachStderr(true)
                    .withAttachStdout(true)
                    .withCmd("/bin/sh", "-c", compileCodeCommand)
                    .exec();

            String compileLogs = runExecAndGetLogs(compileExec.getId(), 5);

            if (!compileLogs.isBlank()) {
                log.error("Compilation error:\n{}", compileLogs);
                return;
            }

            // Run the script

            String inputB64 = Base64.getEncoder().encodeToString("10\n".getBytes());

            String runCodeCommand = String.format("echo \"%s\" | base64 -d | java -XX:MaxRAMPercentage=80.0 -XX:+UseSerialGC -XX:-UsePerfData -cp /tmp %s", inputB64, className);

            ExecCreateCmdResponse execRunCode = dockerClient.execCreateCmd(containerId)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .withCmd("/bin/sh", "-c", runCodeCommand)
                    .exec();

            String runLogs = runExecAndGetLogs(execRunCode.getId(), 3);
            System.out.println("Output:\n" + runLogs);
        } finally {
            try {
                dockerClient.removeContainerCmd(containerId)
                        .withForce(true)
                        .withRemoveVolumes(true)
                        .exec();
            } catch (Exception ignored) {}
        }
    }

    private String getClassNameFromSourceCode(String javaSourceCode) {
        String className = "Main";
        Pattern pattern = Pattern.compile("public\\s+class\\s+([A-Za-z0-9_]+)");
        Matcher matcher = pattern.matcher(javaSourceCode);
        if (matcher.find()) {
            className = matcher.group(1);
        }
        return className;
    }

    private String runExecAndGetLogs(String execId, int timeoutSeconds) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream(256 * 1024);

        try {
            dockerClient.execStartCmd(execId)
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            System.out.println(frame);
                            try {
                                outputStream.write(frame.getPayload());
                            } catch (Exception ignored) {}
                        }
                    })
                    .awaitCompletion(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return outputStream.toString();
    }
}

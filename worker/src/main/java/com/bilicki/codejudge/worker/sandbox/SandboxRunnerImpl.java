package com.bilicki.codejudge.worker.sandbox;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ulimit;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RequiredArgsConstructor
@Slf4j
@Component
public class SandboxRunnerImpl implements SandboxRunner {

    private final DockerClient dockerClient;
    private final SandboxProperties properties;

    @PostConstruct
    protected void initSandboxEnvironment() {
        String image = properties.image();
        try {
            dockerClient.inspectImageCmd(image).exec();
            log.info("Sandbox image '{}' found locally.", image);
        } catch (NotFoundException e) {
            log.info("Sandbox image '{}' not found locally. Pulling from registry...", image);
            try {
                boolean pulled = dockerClient.pullImageCmd(image)
                        .exec(new PullImageResultCallback())
                        .awaitCompletion(5, TimeUnit.MINUTES);

                if (!pulled) {
                    throw new IllegalStateException("Timed out pulling sandbox image: " + image);
                }
                log.info("Successfully pulled sandbox image '{}'.", image);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while pulling sandbox image: " + image, ie);
            }
        }
    }

    @Override
    public SandboxSession openSession() {
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
                .withCmd("/bin/sh", "-c", "sleep " + properties.containerLifetime().toSeconds())
                .exec();

        String containerId = containerResponse.getId();

        try {
            dockerClient.startContainerCmd(containerId).exec();
        } catch (Exception e) {
            log.error("Failed to start sandbox container {}. Cleaning up...", containerId, e);
            try {
                dockerClient.removeContainerCmd(containerId)
                        .withForce(true)
                        .withRemoveVolumes(true)
                        .exec();
            } catch (Exception cleanupException) {
                log.error("Failed to force-remove container {} after startup failure", containerId, cleanupException);
            }
            throw new IllegalStateException("Failed to start container " + containerId, e);
        }

        return new SandboxSessionImpl(dockerClient, containerId, properties);
    }
}

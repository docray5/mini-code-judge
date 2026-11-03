package com.bilicki.codejudge.worker.sandbox;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ulimit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Slf4j
@Component
public class SandboxRunnerImpl implements SandboxRunner {

    private final DockerClient dockerClient;
    private final SandboxProperties properties;

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
            log.error(e.toString());
        }

        return new SandboxSessionImpl(dockerClient, containerId, properties);
    }
}

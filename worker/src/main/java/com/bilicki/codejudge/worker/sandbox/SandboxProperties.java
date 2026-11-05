package com.bilicki.codejudge.worker.sandbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("sandbox")
public record SandboxProperties(
        String dockerHost,
        String image,
        String user,
        String labelKey,
        String labelValue,
        double cpus,
        long memoryMb,
        long pidsLimit,
        long numberOfFilesLimit,
        long tmpfsMb,
        double heapPercentage,
        Duration compileTimeout,
        Duration containerLifetime,
        int maxOutputBytes
) { }

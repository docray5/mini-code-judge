package com.bilicki.codejudge.worker.sandbox;

public record RunTestResult (
        long exitCode,
        String stdout,
        String stderr,
        boolean timedOut,
        boolean outputLimitExceeded,
        boolean oomKilled,
        long durationMs
) { }

package com.bilicki.codejudge.worker;

public record ExecResult(
        long exitCode,
        String stdout,
        String stderr
) {
}

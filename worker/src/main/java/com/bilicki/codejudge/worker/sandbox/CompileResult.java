package com.bilicki.codejudge.worker.sandbox;

public record CompileResult (
        boolean success,
        boolean timedOut,
        String stderr
) { }

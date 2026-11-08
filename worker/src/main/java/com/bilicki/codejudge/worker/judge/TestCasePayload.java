package com.bilicki.codejudge.worker.judge;

import java.util.UUID;

public record TestCasePayload (
        UUID id,
        String input,
        String expectedOutput,
        boolean isSample
) { }

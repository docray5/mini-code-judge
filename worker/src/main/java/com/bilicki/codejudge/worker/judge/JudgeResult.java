package com.bilicki.codejudge.worker.judge;

import java.util.UUID;

public record JudgeResult(
        UUID submissionId,
        Verdict verdict,
        int passedTests,
        int totalTests,
        long maxTestDurationMs,
        String errorMessage
) { }

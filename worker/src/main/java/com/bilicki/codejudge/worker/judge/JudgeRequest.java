package com.bilicki.codejudge.worker.judge;

import java.util.List;
import java.util.UUID;

public record JudgeRequest (
        UUID submissionId,
        String sourceCode,
        int timeLimitSeconds,
        List<TestCasePayload> testCases
) { }

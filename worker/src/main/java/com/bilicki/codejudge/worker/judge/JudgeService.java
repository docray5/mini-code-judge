package com.bilicki.codejudge.worker.judge;

import com.bilicki.codejudge.worker.sandbox.CompileResult;
import com.bilicki.codejudge.worker.sandbox.RunTestResult;
import com.bilicki.codejudge.worker.sandbox.SandboxRunner;
import com.bilicki.codejudge.worker.sandbox.SandboxSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class JudgeService {

    private final SandboxRunner sandboxRunner;
    private final OutputComparator outputComparator;

    public JudgeResult evaluate(JudgeRequest request) {
        List<TestCasePayload> nonSampleTests = request.testCases().stream()
                .filter(tc -> !tc.isSample()).toList();

        int totalTests = nonSampleTests.size();

        try (SandboxSession sandboxSession = sandboxRunner.openSession()) {

            int passedTests = 0;
            long maxTestDurationMs = 0L;

            CompileResult compileResult = sandboxSession.compile(request.sourceCode());

            if (compileResult.timedOut()) {
                return new JudgeResult(request.submissionId(), Verdict.COMPILE_ERROR, passedTests, totalTests, maxTestDurationMs, "Compilation time out");
            }

            if (!compileResult.success()) {
                return new JudgeResult(request.submissionId(), Verdict.COMPILE_ERROR, passedTests, totalTests, maxTestDurationMs, compileResult.stderr());
            }

            for (TestCasePayload testCase : nonSampleTests) {
                RunTestResult runTestResult = sandboxSession.runTest(testCase.input(), request.timeLimitPerTestSeconds());
                maxTestDurationMs = Math.max(maxTestDurationMs, runTestResult.durationMs());

                Verdict verdict = determineTestVerdict(runTestResult, testCase.expectedOutput());

                if (verdict != Verdict.ACCEPTED) {
                    String errorMsg = (verdict == Verdict.RUNTIME_ERROR) ? runTestResult.stderr() : null;
                    return new JudgeResult(request.submissionId(), verdict, passedTests, totalTests, maxTestDurationMs, errorMsg);
                }
                passedTests++;
            }

            return new JudgeResult(request.submissionId(), Verdict.ACCEPTED, passedTests, totalTests, maxTestDurationMs, null);
        } catch (Exception e) {
            log.error("Unexpected error while judging submission {}", request.submissionId(), e);
            return new JudgeResult(request.submissionId(), Verdict.INTERNAL_ERROR, 0 ,totalTests, 0L, "Internal judge error");
        }
    }

    private Verdict determineTestVerdict(RunTestResult run, String expectedOutput) {
        if (run.oomKilled()) {
            return Verdict.MEMORY_LIMIT_EXCEEDED;
        }
        if (run.timedOut()) {
            return Verdict.TIME_LIMIT_EXCEEDED;
        }
        if (run.outputLimitExceeded()) {
            return Verdict.OUTPUT_LIMIT_EXCEEDED;
        }
        if (run.exitCode() != 0) {
            return Verdict.RUNTIME_ERROR;
        }
        if (!outputComparator.matches(run.stdout(), expectedOutput)) {
            return Verdict.WRONG_ANSWER;
        }
        return Verdict.ACCEPTED;
    }
}

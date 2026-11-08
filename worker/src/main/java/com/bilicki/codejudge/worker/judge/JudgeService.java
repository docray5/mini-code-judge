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

    public JudgeResult evaluate(JudgeRequest request) {
        List<TestCasePayload> nonSampleTests = request.testCases().stream()
                .filter(tc -> !tc.isSample()).toList();

        int totalTests = nonSampleTests.size();

        try (SandboxSession sandboxSession = sandboxRunner.openSession()) {

            int passedTests = 0;
            long sessionDurationMs = 0L;

            CompileResult compileResult = sandboxSession.compile(request.sourceCode());

            if (compileResult.timedOut()) {
                return new JudgeResult(request.submissionId(), Verdict.COMPILE_ERROR, passedTests, totalTests, sessionDurationMs, "Compilation time out");
            }

            if (!compileResult.success()) {
                return new JudgeResult(request.submissionId(), Verdict.COMPILE_ERROR, passedTests, totalTests, sessionDurationMs, compileResult.stderr());
            }

            for (TestCasePayload testCase : nonSampleTests) {
                RunTestResult runTestResult = sandboxSession.runTest(testCase.input(), request.timeLimitPerTestSeconds());

                if (runTestResult.exitCode() != 0) {
                    // TODO
                }
            }

            return new JudgeResult(request.submissionId(), Verdict.ACCEPTED, passedTests, totalTests, sessionDurationMs, null);
        } catch (Exception e) {
            log.error("Unexpected error while judging submission {}", request.submissionId(), e);
            return new JudgeResult(request.submissionId(), Verdict.INTERNAL_ERROR, 0 ,totalTests, 0L, "Internal judge error");
        }
    }
}

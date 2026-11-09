package com.bilicki.codejudge.worker.judge;

import com.bilicki.codejudge.worker.sandbox.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class JudgeServiceTest {

    @Mock
    private SandboxRunner sandboxRunner;

    @Mock
    private SandboxSession sandboxSession;

    @InjectMocks
    private JudgeService judgeService;

    @Spy
    private OutputComparator outputComparator;

    @Test
    public void shouldReturnStderrOnCompileError_AndRunZeroTests() {
        when(sandboxRunner.openSession()).thenReturn(sandboxSession);

        when(sandboxSession.compile(any(String.class))).thenReturn(new CompileResult(false, false, "Compile error"));

        UUID submissionId = UUID.randomUUID();
        JudgeResult judgeResult = judgeService.evaluate(new JudgeRequest(submissionId, "bad code", 1,
                List.of(new TestCasePayload(UUID.randomUUID(), "1 2", "3", false))));

        verify(sandboxSession, never()).runTest(anyString(), anyInt());
        verify(sandboxSession).close();

        assertThat(judgeResult.submissionId()).isEqualTo(submissionId);
        assertThat(judgeResult.verdict()).isEqualTo(Verdict.COMPILE_ERROR);
        assertThat(judgeResult.passedTests()).isZero();
        assertThat(judgeResult.totalTests()).isOne();
        assertThat(judgeResult.errorMessage()).isEqualTo("Compile error");
    }

    @Test
    public void shouldReturnCompileErrorOnCompilationTimeOut_AndRunZeroTests() {
        when(sandboxRunner.openSession()).thenReturn(sandboxSession);

        when(sandboxSession.compile(any(String.class))).thenReturn(new CompileResult(false, true, "timeout"));

        UUID submissionId = UUID.randomUUID();
        JudgeResult judgeResult = judgeService.evaluate(new JudgeRequest(submissionId, "some while loop", 1,
                List.of(new TestCasePayload(UUID.randomUUID(), "1 2", "3", false))));

        verify(sandboxSession, never()).runTest(anyString(), anyInt());
        verify(sandboxSession).close();

        assertThat(judgeResult.submissionId()).isEqualTo(submissionId);
        assertThat(judgeResult.verdict()).isEqualTo(Verdict.COMPILE_ERROR);
        assertThat(judgeResult.passedTests()).isZero();
        assertThat(judgeResult.totalTests()).isOne();
        assertThat(judgeResult.errorMessage()).isEqualTo("Compilation time out");
    }

    @Test
    public void shouldReturnInternalErrorOnException() {
        when(sandboxRunner.openSession()).thenReturn(sandboxSession);

        when(sandboxSession.compile(any(String.class))).thenThrow(new RuntimeException());

        UUID submissionId = UUID.randomUUID();
        JudgeResult judgeResult = judgeService.evaluate(new JudgeRequest(submissionId, "bad code", 1,
                List.of(new TestCasePayload(UUID.randomUUID(), "1 2", "3", false))));

        verify(sandboxSession, never()).runTest(anyString(), anyInt());
        verify(sandboxSession).close();

        assertThat(judgeResult.submissionId()).isEqualTo(submissionId);
        assertThat(judgeResult.verdict()).isEqualTo(Verdict.INTERNAL_ERROR);
        assertThat(judgeResult.passedTests()).isZero();
        assertThat(judgeResult.totalTests()).isOne();
        assertThat(judgeResult.errorMessage()).isEqualTo("Internal judge error");
    }

    @Test
    public void shouldReturnAcceptedWhenAllNonSampleTestsPass() {
        when(sandboxRunner.openSession()).thenReturn(sandboxSession);
        when(sandboxSession.compile(anyString())).thenReturn(new CompileResult(true, false, ""));

        when(sandboxSession.runTest(eq("1 2"), eq(2)))
                .thenReturn(new RunTestResult(0L, "3\n", "", false, false, false, 120L));
        when(sandboxSession.runTest(eq("10 20"), eq(2)))
                .thenReturn(new RunTestResult(0L, "30   \r\n", "", false, false, false, 250L));

        UUID submissionId = UUID.randomUUID();
        List<TestCasePayload> tests = List.of(
                new TestCasePayload(UUID.randomUUID(), "1 2", "3", false),
                new TestCasePayload(UUID.randomUUID(), "10 20", "30", false)
        );

        JudgeResult judgeResult = judgeService.evaluate(new JudgeRequest(submissionId, "valid code", 2, tests));

        verify(sandboxSession, times(2)).runTest(anyString(), eq(2));
        verify(sandboxSession).close();

        assertThat(judgeResult.submissionId()).isEqualTo(submissionId);
        assertThat(judgeResult.verdict()).isEqualTo(Verdict.ACCEPTED);
        assertThat(judgeResult.passedTests()).isEqualTo(2);
        assertThat(judgeResult.totalTests()).isEqualTo(2);
        assertThat(judgeResult.errorMessage()).isNull();
    }

    @Test
    public void shouldFilterOutSampleTestsAndOnlyRunHiddenTests() {
        when(sandboxRunner.openSession()).thenReturn(sandboxSession);
        when(sandboxSession.compile(anyString())).thenReturn(new CompileResult(true, false, ""));
        when(sandboxSession.runTest(eq("5 5"), eq(1)))
                .thenReturn(new RunTestResult(0L, "10\n", "", false, false, false, 100L));

        UUID submissionId = UUID.randomUUID();
        List<TestCasePayload> tests = List.of(
                new TestCasePayload(UUID.randomUUID(), "1 1", "2", true),
                new TestCasePayload(UUID.randomUUID(), "5 5", "10", false)
        );

        JudgeResult judgeResult = judgeService.evaluate(new JudgeRequest(submissionId, "valid code", 1, tests));

        verify(sandboxSession, never()).runTest(eq("1 1"), anyInt());
        verify(sandboxSession, times(1)).runTest(eq("5 5"), eq(1));
        verify(sandboxSession).close();

        assertThat(judgeResult.verdict()).isEqualTo(Verdict.ACCEPTED);
        assertThat(judgeResult.passedTests()).isOne();
        assertThat(judgeResult.totalTests()).isOne();
    }

    @Test
    public void shouldStopAtFirstFailureAndReturnWrongAnswer() {
        when(sandboxRunner.openSession()).thenReturn(sandboxSession);
        when(sandboxSession.compile(anyString())).thenReturn(new CompileResult(true, false, ""));

        when(sandboxSession.runTest(eq("1 2"), eq(1)))
                .thenReturn(new RunTestResult(0L, "3\n", "", false, false, false, 100L));
        when(sandboxSession.runTest(eq("2 2"), eq(1)))
                .thenReturn(new RunTestResult(0L, "5\n", "", false, false, false, 110L));

        UUID submissionId = UUID.randomUUID();
        List<TestCasePayload> tests = List.of(
                new TestCasePayload(UUID.randomUUID(), "1 2", "3", false),
                new TestCasePayload(UUID.randomUUID(), "2 2", "4", false),
                new TestCasePayload(UUID.randomUUID(), "3 3", "6", false)
        );

        JudgeResult judgeResult = judgeService.evaluate(new JudgeRequest(submissionId, "code", 1, tests));

        verify(sandboxSession, times(2)).runTest(anyString(), eq(1));
        verify(sandboxSession, never()).runTest(eq("3 3"), anyInt());
        verify(sandboxSession).close();

        assertThat(judgeResult.verdict()).isEqualTo(Verdict.WRONG_ANSWER);
        assertThat(judgeResult.passedTests()).isOne();
        assertThat(judgeResult.totalTests()).isEqualTo(3);
        assertThat(judgeResult.errorMessage()).isNull();
    }

    @Test
    public void shouldReturnRuntimeErrorWithStderrWhenExitCodeIsNonZero() {
        when(sandboxRunner.openSession()).thenReturn(sandboxSession);
        when(sandboxSession.compile(anyString())).thenReturn(new CompileResult(true, false, ""));
        when(sandboxSession.runTest(anyString(), anyInt()))
                .thenReturn(new RunTestResult(1L, "", "Exception in thread \"main\" java.lang.ArithmeticException", false, false, false, 90L));

        UUID submissionId = UUID.randomUUID();
        JudgeResult judgeResult = judgeService.evaluate(new JudgeRequest(submissionId, "code", 1,
                List.of(new TestCasePayload(UUID.randomUUID(), "1 0", "0", false))));

        verify(sandboxSession).close();

        assertThat(judgeResult.verdict()).isEqualTo(Verdict.RUNTIME_ERROR);
        assertThat(judgeResult.passedTests()).isZero();
        assertThat(judgeResult.totalTests()).isOne();
        assertThat(judgeResult.errorMessage()).contains("ArithmeticException");
    }

    @Test
    public void shouldReturnTimeLimitExceededWhenTestTimesOut() {
        when(sandboxRunner.openSession()).thenReturn(sandboxSession);
        when(sandboxSession.compile(anyString())).thenReturn(new CompileResult(true, false, ""));
        when(sandboxSession.runTest(anyString(), anyInt()))
                .thenReturn(new RunTestResult(124L, "", "", true, false, false, 1000L));

        UUID submissionId = UUID.randomUUID();
        JudgeResult judgeResult = judgeService.evaluate(new JudgeRequest(submissionId, "while(true)", 1,
                List.of(new TestCasePayload(UUID.randomUUID(), "", "ok", false))));

        verify(sandboxSession).close();

        assertThat(judgeResult.verdict()).isEqualTo(Verdict.TIME_LIMIT_EXCEEDED);
        assertThat(judgeResult.passedTests()).isZero();
        assertThat(judgeResult.totalTests()).isOne();
    }

    @Test
    public void shouldReturnMemoryLimitExceededWhenOomKilled() {
        when(sandboxRunner.openSession()).thenReturn(sandboxSession);
        when(sandboxSession.compile(anyString())).thenReturn(new CompileResult(true, false, ""));
        when(sandboxSession.runTest(anyString(), anyInt()))
                .thenReturn(new RunTestResult(137L, "", "java.lang.OutOfMemoryError", false, false, true, 300L));

        UUID submissionId = UUID.randomUUID();
        JudgeResult judgeResult = judgeService.evaluate(new JudgeRequest(submissionId, "oom code", 1,
                List.of(new TestCasePayload(UUID.randomUUID(), "", "ok", false))));

        verify(sandboxSession).close();

        assertThat(judgeResult.verdict()).isEqualTo(Verdict.MEMORY_LIMIT_EXCEEDED);
        assertThat(judgeResult.passedTests()).isZero();
        assertThat(judgeResult.totalTests()).isOne();
    }

    @Test
    public void shouldReturnOutputLimitExceededWhenStdoutExceedsCap() {
        when(sandboxRunner.openSession()).thenReturn(sandboxSession);
        when(sandboxSession.compile(anyString())).thenReturn(new CompileResult(true, false, ""));
        when(sandboxSession.runTest(anyString(), anyInt()))
                .thenReturn(new RunTestResult(0L, "truncated...", "", false, true, false, 150L));

        UUID submissionId = UUID.randomUUID();
        JudgeResult judgeResult = judgeService.evaluate(new JudgeRequest(submissionId, "stdout bomb", 1,
                List.of(new TestCasePayload(UUID.randomUUID(), "", "ok", false))));

        verify(sandboxSession).close();

        assertThat(judgeResult.verdict()).isEqualTo(Verdict.OUTPUT_LIMIT_EXCEEDED);
        assertThat(judgeResult.passedTests()).isZero();
        assertThat(judgeResult.totalTests()).isOne();
    }
}

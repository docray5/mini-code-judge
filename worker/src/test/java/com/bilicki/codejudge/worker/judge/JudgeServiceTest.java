package com.bilicki.codejudge.worker.judge;

import com.bilicki.codejudge.worker.sandbox.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
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
}

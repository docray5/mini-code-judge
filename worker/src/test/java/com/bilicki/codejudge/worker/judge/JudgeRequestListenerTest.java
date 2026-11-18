package com.bilicki.codejudge.worker.judge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class JudgeRequestListenerTest {

    @Mock
    private JudgeService judgeService;

    @Mock
    private JudgeResultPublisher judgeResultPublisher;

    @InjectMocks
    private JudgeRequestListener listener;

    @Test
    public void shouldEvaluateRequestAndPublishResult() {
        UUID submissionId = UUID.randomUUID();
        JudgeRequest request = new JudgeRequest(submissionId, "code", 1, List.of());
        JudgeResult expectedResult = new JudgeResult(submissionId, Verdict.ACCEPTED, 1, 1, 50L, null);

        when(judgeService.evaluate(request)).thenReturn(expectedResult);

        listener.onJudgeRequest(request);

        verify(judgeResultPublisher).publish(expectedResult);
    }

    @Test
    public void shouldPublishInternalErrorWhenJudgeServiceThrowsUnexpectedException() {
        UUID submissionId = UUID.randomUUID();
        JudgeRequest request = new JudgeRequest(submissionId, "code", 1, List.of());

        when(judgeService.evaluate(request)).thenThrow(new RuntimeException("Unexpected crash"));

        listener.onJudgeRequest(request);

        ArgumentCaptor<JudgeResult> captor = ArgumentCaptor.forClass(JudgeResult.class);
        verify(judgeResultPublisher).publish(captor.capture());

        assertThat(captor.getValue().submissionId()).isEqualTo(submissionId);
        assertThat(captor.getValue().verdict()).isEqualTo(Verdict.INTERNAL_ERROR);
    }
}

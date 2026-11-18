package com.bilicki.codejudge.worker.judge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers
public class JudgeMessagingIntegrationTest {
    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:4-management-alpine");

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @MockitoBean
    private JudgeService judgeService;

    @Value("${judge.queues.requests}")
    private String requestsQueue;

    @Value("${judge.queues.results}")
    private String resultsQueue;

    @BeforeEach
    public void purgeQueues() {
        amqpAdmin.purgeQueue(requestsQueue, false);
        amqpAdmin.purgeQueue(resultsQueue, false);
    }

    @Test
    public void shouldConsumeJudgeRequestFromQueueAndPublishJudgeResultToResultsQueue() {
        UUID submissionId = UUID.randomUUID();
        JudgeRequest request = new JudgeRequest(
                submissionId,
                "public class Main {}",
                2,
                List.of(new TestCasePayload(UUID.randomUUID(), "1 2", "3", false))
        );
        JudgeResult expectedResult = new JudgeResult(
                submissionId,
                Verdict.ACCEPTED,
                1,
                1,
                125L,
                null
        );

        when(judgeService.evaluate(any(JudgeRequest.class))).thenReturn(expectedResult);

        rabbitTemplate.convertAndSend(requestsQueue, request);

        JudgeResult actualResult = rabbitTemplate.receiveAndConvert(
                resultsQueue,
                5000L,
                new ParameterizedTypeReference<JudgeResult>() {}
        );

        assertThat(actualResult).isNotNull();
        assertThat(actualResult.submissionId()).isEqualTo(submissionId);
        assertThat(actualResult.verdict()).isEqualTo(Verdict.ACCEPTED);
        assertThat(actualResult.passedTests()).isOne();
        assertThat(actualResult.totalTests()).isOne();
        assertThat(actualResult.maxTestDurationMs()).isEqualTo(125L);

        verify(judgeService).evaluate(request);
    }

    @Test
    public void shouldPublishInternalErrorResultWhenJudgeServiceThrowsUnexpectedException() {
        UUID submissionId = UUID.randomUUID();
        JudgeRequest request = new JudgeRequest(
                submissionId,
                "public class Main {}",
                2,
                List.of()
        );

        when(judgeService.evaluate(any(JudgeRequest.class)))
                .thenThrow(new RuntimeException("Simulated unexpected crash"));

        rabbitTemplate.convertAndSend(requestsQueue, request);

        JudgeResult actualResult = rabbitTemplate.receiveAndConvert(
                resultsQueue,
                5000L,
                new ParameterizedTypeReference<JudgeResult>() {}
        );

        assertThat(actualResult).isNotNull();
        assertThat(actualResult.submissionId()).isEqualTo(submissionId);
        assertThat(actualResult.verdict()).isEqualTo(Verdict.INTERNAL_ERROR);
        assertThat(actualResult.errorMessage()).isEqualTo("Internal judge error");
    }
}

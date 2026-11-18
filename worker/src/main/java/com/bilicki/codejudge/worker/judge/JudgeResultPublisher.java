package com.bilicki.codejudge.worker.judge;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class JudgeResultPublisher {
    private final RabbitTemplate rabbitTemplate;
    private final String resultsQueue;

    public JudgeResultPublisher(RabbitTemplate rabbitTemplate, @Value("${judge.queues.results}") String resultsQueue) {
        this.rabbitTemplate = rabbitTemplate;
        this.resultsQueue = resultsQueue;
    }

    public void publish(JudgeResult result) {
        log.info("Publishing verdict {} for submission {}", result.verdict(), result.submissionId());
        rabbitTemplate.convertAndSend(resultsQueue, result);
    }
}

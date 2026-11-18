package com.bilicki.codejudge.worker.judge;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class JudgeRequestListener {
    private final JudgeService judgeService;
    private final JudgeResultPublisher judgeResultPublisher;

    @RabbitListener(queues = "${judge.queues.requests}")
    public void onJudgeRequest(JudgeRequest judgeRequest) {
        log.info("Received judge request for submission {}", judgeRequest.submissionId());
        JudgeResult result;
        try {
            result = judgeService.evaluate(judgeRequest);
        } catch (Exception e) {
            log.error("Unhandled error while evaluating submission {}", judgeRequest.submissionId());
            result = new JudgeResult(
                    judgeRequest.submissionId(),
                    Verdict.INTERNAL_ERROR,
                    0,
                    0,
                    0L,
                    "Internal judge error"
            );
        }
        judgeResultPublisher.publish(result);
    }
}

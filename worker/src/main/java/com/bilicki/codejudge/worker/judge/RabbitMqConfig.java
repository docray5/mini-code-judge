package com.bilicki.codejudge.worker.judge;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {
    @Bean
    public Queue judgeRequestsQueue(@Value("${judge.queues.requests}") String queueName) {
        return QueueBuilder.durable(queueName).build();
    }

    @Bean
    public Queue judgeResultsQueue(@Value("${judge.queues.results}") String queueName) {
        return QueueBuilder.durable(queueName).build();
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }
}

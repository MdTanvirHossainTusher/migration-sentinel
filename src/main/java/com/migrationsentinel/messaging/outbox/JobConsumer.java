package com.migrationsentinel.messaging.outbox;

import com.migrationsentinel.messaging.JobExecutionService;
import com.migrationsentinel.messaging.JobMessages;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The worker end of the queue. Each record is one job; the offset is committed per record
 * (see {@code KafkaConfig}), so a job is only acknowledged once it has actually run. Scale
 * throughput by scaling replicas — the consumer group hands each partition to one pod.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "sentinel.messaging", name = "transport", havingValue = "kafka")
public class JobConsumer {

    private final JobExecutionService executor;

    @KafkaListener(topics = JobMessages.TOPIC_REVIEWS, containerFactory = "kafkaListenerContainerFactory")
    public void onReview(Map<String, Object> message) {
        UUID jobId = UUID.fromString((String) message.get("jobId"));
        log.info("consuming review job {}", jobId);
        executor.executeReview(jobId);
    }

    @SuppressWarnings("unchecked")
    @KafkaListener(topics = JobMessages.TOPIC_EVALUATIONS, containerFactory = "kafkaListenerContainerFactory")
    public void onEvaluation(Map<String, Object> message) {
        UUID runId = UUID.fromString((String) message.get("runId"));
        List<String> caseIds = (List<String>) message.getOrDefault("caseIds", List.of());
        log.info("consuming evaluation run {}", runId);
        executor.executeEvaluation(runId, caseIds);
    }
}

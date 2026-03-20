package com.example.demo.mq;

import com.example.demo.model.dto.AddPointsTransactionArg;
import com.example.demo.model.message.PointsEventMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * RocketMQ Message Producer
 *
 * Supports two sending modes:
 * 1. Normal sync send (sendPointsEvent) - for simple scenarios
 * 2. Transactional message send (sendTransactionalPointsEvent) - for consistency guarantee
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PointsEventProducer {

    private static final String TOPIC = "user-points-topic";

    private final RocketMQTemplate rocketMQTemplate;

    /**
     * Send transactional message.
     *
     * Workflow:
     * 1. Send Half Message (invisible to Consumer)
     * 2. Execute local transaction (in PointsTransactionListener)
     * 3. COMMIT or ROLLBACK message based on local transaction result
     *
     * This guarantees:
     * - DB write success -> message will be delivered
     * - DB write failure -> message will not be delivered
     *
     * @param userId User ID
     * @param amount Points amount
     * @param reason Reason
     * @return Transaction ID
     */
    public String sendTransactionalPointsEvent(String userId, Integer amount, String reason) {
        // Generate unique transaction ID
        String transactionId = "points_" + UUID.randomUUID().toString().replace("-", "");

        // Build message content (totalPoints is 0 temporarily, will be updated after local transaction)
        PointsEventMessage eventMessage = new PointsEventMessage();
        eventMessage.setEventId(transactionId);
        eventMessage.setEventType("ADD");
        eventMessage.setUserId(userId);
        eventMessage.setAmount(amount);
        eventMessage.setTotalPoints(0L); // Temporarily set to 0, Consumer will read latest value from Redis
        eventMessage.setReason(reason);
        eventMessage.setTimestamp(LocalDateTime.now());

        // Build Spring Message
        Message<PointsEventMessage> message = MessageBuilder
                .withPayload(eventMessage)
                .build();

        // Build local transaction argument
        AddPointsTransactionArg txArg = new AddPointsTransactionArg(
                transactionId, userId, amount, reason
        );

        log.info("Sending transactional message: transactionId={}, userId={}, amount={}",
                transactionId, userId, amount);

        // Send transactional message
        // This triggers PointsTransactionListener.executeLocalTransaction()
        rocketMQTemplate.sendMessageInTransaction(TOPIC, message, txArg);

        return transactionId;
    }

    /**
     * Normal sync send (kept for other scenarios)
     */
    public void sendPointsEvent(Long recordId, String userId, Integer amount, Long totalPoints, String reason) {
        PointsEventMessage message = new PointsEventMessage();
        message.setEventId("points_" + recordId);
        message.setEventType("ADD");
        message.setUserId(userId);
        message.setAmount(amount);
        message.setTotalPoints(totalPoints);
        message.setReason(reason);
        message.setTimestamp(LocalDateTime.now());

        try {
            rocketMQTemplate.syncSend(TOPIC, message);
            log.info("Message sent successfully: eventId={}, userId={}", message.getEventId(), userId);
        } catch (Exception e) {
            log.error("Failed to send message: eventId={}, userId={}", message.getEventId(), userId, e);
            throw e;
        }
    }
}

package com.example.demo.mq;

import com.example.demo.model.message.PointsEventMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class PointsEventProducer {

    private static final String TOPIC = "user-points-topic";

    private final RocketMQTemplate rocketMQTemplate;

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

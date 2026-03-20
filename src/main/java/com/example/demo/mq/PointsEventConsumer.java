package com.example.demo.mq;

import com.example.demo.cache.PointsCacheService;
import com.example.demo.model.message.PointsEventMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "user-points-topic",
        consumerGroup = "user-points-consumer-group"
)
public class PointsEventConsumer implements RocketMQListener<PointsEventMessage> {

    private static final String PROCESSED_KEY_PREFIX = "POINTS:EVENT:PROCESSED:";
    private static final String LOCK_KEY_PREFIX = "POINTS:EVENT:LOCK:";
    private static final long PROCESSED_TTL_HOURS = 24;
    private static final long LOCK_TTL_SECONDS = 30;

    private final PointsCacheService cacheService;
    private final StringRedisTemplate redisTemplate;

    @Override
    public void onMessage(PointsEventMessage message) {
        String eventId = message.getEventId();
        String userId = message.getUserId();

        log.info("Received message: eventId={}, userId={}, type={}",
                eventId, userId, message.getEventType());

        // Step 1: Check if already processed
        String processedKey = PROCESSED_KEY_PREFIX + eventId;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(processedKey))) {
            log.info("Event already processed, skip: {}", eventId);
            return;
        }

        // Step 2: Acquire processing lock
        String lockKey = LOCK_KEY_PREFIX + eventId;
        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, "1", LOCK_TTL_SECONDS, TimeUnit.SECONDS);

        if (!Boolean.TRUE.equals(locked)) {
            log.info("Event is being processed by another consumer: {}", eventId);
            throw new RuntimeException("Event is being processed, retry later");
        }

        try {
            // Step 3: Double-check after lock
            if (Boolean.TRUE.equals(redisTemplate.hasKey(processedKey))) {
                log.info("Event already processed (double check), skip: {}", eventId);
                return;
            }

            // Step 4: Process event
            processEvent(message);

            // Step 5: Mark as processed
            redisTemplate.opsForValue()
                    .set(processedKey, "1", PROCESSED_TTL_HOURS, TimeUnit.HOURS);

            log.info("Event processed successfully: {}", eventId);

        } catch (Exception e) {
            log.error("Failed to process event: {}", eventId, e);
            throw e;
        } finally {
            // Step 6: Release lock
            redisTemplate.delete(lockKey);
        }
    }

    private void processEvent(PointsEventMessage message) {
        String userId = message.getUserId();
        Long totalPoints = message.getTotalPoints();

        // Update cache (idempotent: SET overwrites)
        cacheService.setUserPoints(userId, totalPoints);

        // Update leaderboard (idempotent: ZADD overwrites score)
        cacheService.updateLeaderboard(userId, totalPoints);
    }
}

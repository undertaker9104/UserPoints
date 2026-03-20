package com.example.demo.mq;

import com.example.demo.cache.PointsCacheService;
import com.example.demo.model.message.PointsEventMessage;
import com.example.demo.repository.UserPointsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * RocketMQ Message Consumer
 *
 * Processes points event messages from Producer, updates Redis cache and leaderboard.
 *
 * Idempotency guarantee:
 * 1. Check if PROCESSED key exists
 * 2. Acquire processing lock to prevent concurrent consumption
 * 3. Double-check after acquiring lock
 * 4. Use SET/ZADD (overwrite operations) to ensure same result on multiple executions
 */
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
    private final UserPointsRepository userPointsRepository;

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
        String eventId = message.getEventId();
        String userId = message.getUserId();

        // Always query DB for the latest totalPoints to avoid race conditions
        // when multiple events for the same user are processed concurrently.
        // The DB is the source of truth, and this ensures cache consistency.
        Long totalPoints = userPointsRepository.findByUserId(userId)
                .map(up -> up.getTotalPoints())
                .orElse(null);

        if (totalPoints == null) {
            log.error("User not found in DB for event: {}, userId={}", eventId, userId);
            throw new RuntimeException("User not found in DB");
        }

        // Update cache (idempotent: SET overwrites)
        cacheService.setUserPoints(userId, totalPoints);

        // Update leaderboard (idempotent: ZADD overwrites score)
        cacheService.updateLeaderboard(userId, totalPoints);

        log.info("Cache and leaderboard updated: userId={}, totalPoints={}", userId, totalPoints);
    }

}

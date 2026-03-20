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
    private static final String TX_RESULT_KEY_PREFIX = "POINTS:TX:RESULT:";
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
        Long totalPoints = message.getTotalPoints();

        // If totalPoints is 0, this is a transactional message,
        // need to get actual value from Redis or DB
        if (totalPoints == null || totalPoints == 0L) {
            totalPoints = getTotalPointsFromTransactionResult(eventId, userId);
        }

        if (totalPoints == null) {
            log.error("Cannot determine totalPoints for event: {}", eventId);
            throw new RuntimeException("Cannot determine totalPoints");
        }

        // Update cache (idempotent: SET overwrites)
        cacheService.setUserPoints(userId, totalPoints);

        // Update leaderboard (idempotent: ZADD overwrites score)
        cacheService.updateLeaderboard(userId, totalPoints);

        log.info("Cache and leaderboard updated: userId={}, totalPoints={}", userId, totalPoints);
    }

    /**
     * Get actual totalPoints from transaction result or database.
     */
    private Long getTotalPointsFromTransactionResult(String eventId, String userId) {
        // First try to get from transaction result Redis key
        String resultKey = TX_RESULT_KEY_PREFIX + eventId;
        String result = redisTemplate.opsForValue().get(resultKey);

        if (result != null) {
            log.debug("Got totalPoints from transaction result: eventId={}, totalPoints={}", eventId, result);
            return Long.parseLong(result);
        }

        // If not in Redis, query from database
        log.debug("Transaction result not found in Redis, querying DB: userId={}", userId);
        return userPointsRepository.findByUserId(userId)
                .map(up -> {
                    log.debug("Got totalPoints from DB: userId={}, totalPoints={}", userId, up.getTotalPoints());
                    return up.getTotalPoints();
                })
                .orElse(null);
    }
}

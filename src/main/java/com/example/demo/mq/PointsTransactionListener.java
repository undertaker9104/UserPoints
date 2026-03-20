package com.example.demo.mq;

import com.example.demo.exception.BusinessException;
import com.example.demo.exception.ErrorCode;
import com.example.demo.lock.RedisLock;
import com.example.demo.model.dto.AddPointsTransactionArg;
import com.example.demo.model.entity.PointRecord;
import com.example.demo.model.entity.UserPoints;
import com.example.demo.model.message.PointsEventMessage;
import com.example.demo.repository.PointRecordRepository;
import com.example.demo.repository.UserPointsRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

/**
 * RocketMQ Transaction Message Listener
 *
 * Workflow:
 * 1. Producer sends Half Message (prepared message, invisible to Consumer)
 * 2. RocketMQ Broker returns send success
 * 3. Execute local transaction (executeLocalTransaction)
 * 4. Return COMMIT / ROLLBACK / UNKNOWN based on local transaction result
 * 5. If UNKNOWN or timeout, Broker will check back (checkLocalTransaction)
 *
 * This guarantees: DB write success <-> MQ message delivery consistency
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQTransactionListener
public class PointsTransactionListener implements RocketMQLocalTransactionListener {

    private static final String TRANSACTION_STATUS_KEY = "POINTS:TX:STATUS:";
    private static final long TRANSACTION_STATUS_TTL_HOURS = 24;

    private final PointRecordRepository pointRecordRepository;
    private final UserPointsRepository userPointsRepository;
    private final RedisLock redisLock;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Execute local transaction.
     * Called after Half Message is sent successfully.
     *
     * @param message Message containing PointsEventMessage
     * @param arg     Local transaction argument AddPointsTransactionArg
     * @return Transaction state: COMMIT, ROLLBACK, or UNKNOWN (wait for check back)
     */
    @Override
    @Transactional
    public RocketMQLocalTransactionState executeLocalTransaction(Message message, Object arg) {
        AddPointsTransactionArg txArg = (AddPointsTransactionArg) arg;
        String transactionId = txArg.getTransactionId();
        String userId = txArg.getUserId();

        log.info("Executing local transaction: transactionId={}, userId={}", transactionId, userId);

        // Step 1: Acquire distributed lock
        if (!redisLock.lock(RedisLock.LockType.USER_POINTS, userId)) {
            log.warn("Failed to acquire lock for user: {}", userId);
            // Save ROLLBACK status so waitForTransactionResult can detect it quickly
            saveTransactionStatus(transactionId, "ROLLBACK", null);
            return RocketMQLocalTransactionState.ROLLBACK;
        }

        try {
            // Step 2: Execute local transaction (DB operations)
            Long newTotal = executePointsTransaction(txArg);

            // Step 3: Save transaction status to Redis (for check back)
            saveTransactionStatus(transactionId, "COMMIT", newTotal);

            // Step 4: Save transaction result to Redis
            saveTransactionResult(transactionId, newTotal);

            log.info("Local transaction committed: transactionId={}, userId={}, newTotal={}",
                    transactionId, userId, newTotal);

            return RocketMQLocalTransactionState.COMMIT;

        } catch (BusinessException e) {
            log.warn("Business exception in local transaction: transactionId={}, error={}",
                    transactionId, e.getMessage());
            saveTransactionStatus(transactionId, "ROLLBACK", null);
            return RocketMQLocalTransactionState.ROLLBACK;

        } catch (Exception e) {
            log.error("Exception in local transaction: transactionId={}", transactionId, e);
            // Return UNKNOWN, let Broker check back later
            saveTransactionStatus(transactionId, "UNKNOWN", null);
            return RocketMQLocalTransactionState.UNKNOWN;

        } finally {
            redisLock.unlock(RedisLock.LockType.USER_POINTS, userId);
        }
    }

    /**
     * Transaction check back.
     * Called when executeLocalTransaction returns UNKNOWN or times out.
     *
     * @param message Message
     * @return Transaction state
     */
    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message message) {
        try {
            // Extract transactionId from message
            String payload = new String((byte[]) message.getPayload());
            PointsEventMessage eventMessage = objectMapper.readValue(payload, PointsEventMessage.class);
            String transactionId = eventMessage.getEventId();

            log.info("Checking local transaction: transactionId={}", transactionId);

            // Query transaction status from Redis
            String status = getTransactionStatus(transactionId);

            if ("COMMIT".equals(status)) {
                log.info("Transaction check result: COMMIT, transactionId={}", transactionId);
                return RocketMQLocalTransactionState.COMMIT;
            } else if ("ROLLBACK".equals(status)) {
                log.info("Transaction check result: ROLLBACK, transactionId={}", transactionId);
                return RocketMQLocalTransactionState.ROLLBACK;
            } else {
                // If status unknown, check if record exists in database by transactionId
                boolean exists = pointRecordRepository.existsByTransactionId(transactionId);

                if (exists) {
                    log.info("Transaction check: record exists, COMMIT, transactionId={}", transactionId);
                    saveTransactionStatus(transactionId, "COMMIT", null);
                    return RocketMQLocalTransactionState.COMMIT;
                } else {
                    log.info("Transaction check: record not found, ROLLBACK, transactionId={}", transactionId);
                    saveTransactionStatus(transactionId, "ROLLBACK", null);
                    return RocketMQLocalTransactionState.ROLLBACK;
                }
            }
        } catch (Exception e) {
            log.error("Error checking local transaction", e);
            // Continue returning UNKNOWN, let Broker check again later
            return RocketMQLocalTransactionState.UNKNOWN;
        }
    }

    /**
     * Execute points-related database operations.
     */
    private Long executePointsTransaction(AddPointsTransactionArg arg) {
        String userId = arg.getUserId();
        Integer amount = arg.getAmount();
        String reason = arg.getReason();

        // Query or create user points
        UserPoints userPoints = userPointsRepository.findByUserId(userId).orElse(null);

        Long newTotal;
        if (userPoints == null) {
            // New user
            userPoints = new UserPoints(userId, (long) amount);
            userPointsRepository.save(userPoints);
            newTotal = (long) amount;
        } else {
            // Existing user - optimistic lock update
            int updated = userPointsRepository.updatePointsWithOptimisticLock(
                    userId, amount, userPoints.getVersion()
            );
            if (updated == 0) {
                throw new BusinessException(ErrorCode.CONFLICT, "Concurrent update detected");
            }
            newTotal = userPoints.getTotalPoints() + amount;
        }

        // Save point record with transactionId for check back
        PointRecord record = new PointRecord(userId, amount, reason, arg.getTransactionId());
        pointRecordRepository.save(record);

        return newTotal;
    }

    private void saveTransactionStatus(String transactionId, String status, Long totalPoints) {
        String key = TRANSACTION_STATUS_KEY + transactionId;
        String value = status + (totalPoints != null ? ":" + totalPoints : "");
        redisTemplate.opsForValue().set(key, value, TRANSACTION_STATUS_TTL_HOURS, TimeUnit.HOURS);
    }

    private String getTransactionStatus(String transactionId) {
        String key = TRANSACTION_STATUS_KEY + transactionId;
        String value = redisTemplate.opsForValue().get(key);
        if (value != null && value.contains(":")) {
            return value.split(":")[0];
        }
        return value;
    }

    private void saveTransactionResult(String transactionId, Long totalPoints) {
        String key = "POINTS:TX:RESULT:" + transactionId;
        redisTemplate.opsForValue().set(key, String.valueOf(totalPoints), 1, TimeUnit.HOURS);
    }
}

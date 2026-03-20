package com.example.demo.service;

import com.example.demo.cache.PointsCacheService;
import com.example.demo.exception.BusinessException;
import com.example.demo.exception.ErrorCode;
import com.example.demo.lock.RedisLock;
import com.example.demo.model.dto.AddPointsRequest;
import com.example.demo.model.dto.PointsResponse;
import com.example.demo.model.dto.UpdateReasonRequest;
import com.example.demo.model.entity.PointRecord;
import com.example.demo.model.entity.UserPoints;
import com.example.demo.mq.PointsEventProducer;
import com.example.demo.repository.PointRecordRepository;
import com.example.demo.repository.UserPointsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PointsService {

    private final PointRecordRepository pointRecordRepository;
    private final UserPointsRepository userPointsRepository;
    private final RedisLock redisLock;
    private final PointsCacheService cacheService;
    private final PointsEventProducer producer;
    private final StringRedisTemplate redisTemplate;

    private static final int MAX_WAIT_RETRIES = 50;  // Max wait 5 seconds (50 * 100ms)
    private static final int MAX_CACHE_LOAD_RETRIES = 3;

    /**
     * Add points - using RocketMQ transactional message
     *
     * Workflow:
     * 1. Send Half Message to RocketMQ
     * 2. Execute local transaction in TransactionListener (acquire lock -> update DB)
     * 3. COMMIT message if local transaction succeeds, ROLLBACK if fails
     * 4. Consumer receives message and updates Redis cache
     *
     * This guarantees DB and MQ consistency:
     * - DB success -> message will be delivered
     * - DB failure -> message will not be delivered
     */
    public PointsResponse addPoints(AddPointsRequest request) {
        String userId = request.getUserId();

        log.info("Adding points with transactional message: userId={}, amount={}",
                userId, request.getAmount());

        // Send transactional message (local transaction executed in TransactionListener)
        String transactionId = producer.sendTransactionalPointsEvent(
                userId, request.getAmount(), request.getReason()
        );

        // Wait for transaction completion and get result
        Long newTotal = waitForTransactionResult(transactionId);

        if (newTotal == null) {
            // Transaction may have failed or timed out
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Transaction timeout or failed");
        }

        log.info("Points added successfully: userId={}, amount={}, total={}",
                userId, request.getAmount(), newTotal);

        return new PointsResponse(userId, newTotal);
    }

    /**
     * Wait for transaction result.
     * TransactionListener writes result to Redis.
     */
    private Long waitForTransactionResult(String transactionId) {
        String resultKey = "POINTS:TX:RESULT:" + transactionId;
        String statusKey = "POINTS:TX:STATUS:" + transactionId;

        for (int i = 0; i < MAX_WAIT_RETRIES; i++) {
            // Check result first
            String result = redisTemplate.opsForValue().get(resultKey);
            if (result != null) {
                return Long.parseLong(result);
            }

            // Check status, if ROLLBACK then return failure immediately
            String status = redisTemplate.opsForValue().get(statusKey);
            if ("ROLLBACK".equals(status)) {
                throw new BusinessException(ErrorCode.CONFLICT, "Transaction rolled back, please retry");
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Interrupted while waiting for transaction");
            }
        }

        return null;
    }

    /**
     * Get user points.
     */
    public PointsResponse getPoints(String userId) {
        // Step 1: Check cache
        Long cachedPoints = cacheService.getUserPoints(userId);
        if (cachedPoints != null) {
            return new PointsResponse(userId, cachedPoints);
        }

        // Step 2: Check if null marker exists
        if (cacheService.hasKey(userId)) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        // Step 3: Acquire cache load lock with bounded retry
        int retries = 0;
        while (!redisLock.lock(RedisLock.LockType.CACHE_LOAD, userId)) {
            retries++;
            if (retries >= MAX_CACHE_LOAD_RETRIES) {
                throw new BusinessException(ErrorCode.CONFLICT, "Unable to acquire cache load lock after retries");
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException(ErrorCode.CONFLICT, "Interrupted while waiting for cache load lock");
            }
            // Check cache again after waiting
            cachedPoints = cacheService.getUserPoints(userId);
            if (cachedPoints != null) {
                return new PointsResponse(userId, cachedPoints);
            }
        }

        try {
            // Step 4: Double-check cache
            cachedPoints = cacheService.getUserPoints(userId);
            if (cachedPoints != null) {
                return new PointsResponse(userId, cachedPoints);
            }

            // Step 5: Query database
            UserPoints userPoints = userPointsRepository.findByUserId(userId)
                    .orElse(null);

            if (userPoints == null) {
                cacheService.setUserPointsNull(userId);
                throw new BusinessException(ErrorCode.USER_NOT_FOUND);
            }

            // Step 6: Update cache
            cacheService.setUserPoints(userId, userPoints.getTotalPoints());

            return new PointsResponse(userId, userPoints.getTotalPoints());

        } finally {
            redisLock.unlock(RedisLock.LockType.CACHE_LOAD, userId);
        }
    }

    @Transactional
    public PointRecord updateReason(Long id, UpdateReasonRequest request) {
        PointRecord record = pointRecordRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.RECORD_NOT_FOUND));

        record.setReason(request.getReason());
        return pointRecordRepository.save(record);
    }

    @Transactional
    public void deleteUserPoints(String userId) {
        // Step 1: Acquire lock
        if (!redisLock.lock(RedisLock.LockType.USER_POINTS, userId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "Operation in progress, please retry");
        }

        try {
            // Step 2: Check user exists
            if (!userPointsRepository.existsByUserId(userId)) {
                throw new BusinessException(ErrorCode.USER_NOT_FOUND);
            }

            // Step 3: Delete from MySQL
            pointRecordRepository.deleteByUserId(userId);
            userPointsRepository.deleteById(userId);

            // Step 4: Delete from Redis
            cacheService.deleteUserPoints(userId);
            cacheService.removeFromLeaderboard(userId);

            log.info("User points deleted: userId={}", userId);

        } finally {
            redisLock.unlock(RedisLock.LockType.USER_POINTS, userId);
        }
    }
}

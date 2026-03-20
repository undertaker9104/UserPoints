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

    @Transactional
    public PointsResponse addPoints(AddPointsRequest request) {
        String userId = request.getUserId();

        // Step 1: Acquire lock
        if (!redisLock.lock(RedisLock.LockType.USER_POINTS, userId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "Operation in progress, please retry");
        }

        try {
            // Step 2: Get or create user points
            UserPoints userPoints = userPointsRepository.findByUserId(userId)
                    .orElse(null);

            Long newTotal;
            if (userPoints == null) {
                // New user
                userPoints = new UserPoints(userId, (long) request.getAmount());
                userPointsRepository.save(userPoints);
                newTotal = (long) request.getAmount();
            } else {
                // Existing user - optimistic lock update
                int updated = userPointsRepository.updatePointsWithOptimisticLock(
                        userId, request.getAmount(), userPoints.getVersion()
                );
                if (updated == 0) {
                    throw new BusinessException(ErrorCode.CONFLICT, "Concurrent update detected, please retry");
                }
                newTotal = userPoints.getTotalPoints() + request.getAmount();
            }

            // Step 3: Save point record
            PointRecord record = new PointRecord(userId, request.getAmount(), request.getReason());
            PointRecord savedRecord = pointRecordRepository.save(record);

            // Step 4: Send MQ message
            producer.sendPointsEvent(savedRecord.getId(), userId, request.getAmount(), newTotal, request.getReason());

            log.info("Points added: userId={}, amount={}, total={}", userId, request.getAmount(), newTotal);
            return new PointsResponse(userId, newTotal);

        } finally {
            redisLock.unlock(RedisLock.LockType.USER_POINTS, userId);
        }
    }

    private static final int MAX_CACHE_LOAD_RETRIES = 3;

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

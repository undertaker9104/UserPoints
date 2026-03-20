package com.example.demo.unit.service;

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
import com.example.demo.service.PointsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PointsServiceTest {

    @Mock
    private PointRecordRepository pointRecordRepository;

    @Mock
    private UserPointsRepository userPointsRepository;

    @Mock
    private RedisLock redisLock;

    @Mock
    private PointsCacheService cacheService;

    @Mock
    private PointsEventProducer producer;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private PointsService pointsService;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        pointsService = new PointsService(
                pointRecordRepository, userPointsRepository,
                redisLock, cacheService, producer, redisTemplate
        );
    }

    @Test
    void should_addPoints_when_transactionSucceeds() {
        // Given
        AddPointsRequest request = new AddPointsRequest("user_123", 100, "bonus");
        String transactionId = "points_abc123";

        when(producer.sendTransactionalPointsEvent("user_123", 100, "bonus"))
                .thenReturn(transactionId);
        when(valueOperations.get("POINTS:TX:RESULT:" + transactionId))
                .thenReturn("600");

        // When
        PointsResponse response = pointsService.addPoints(request);

        // Then
        assertThat(response.getUserId()).isEqualTo("user_123");
        assertThat(response.getTotalPoints()).isEqualTo(600L);
        verify(producer).sendTransactionalPointsEvent("user_123", 100, "bonus");
    }

    @Test
    void should_throwException_when_transactionRollback() {
        // Given
        AddPointsRequest request = new AddPointsRequest("user_123", 100, "bonus");
        String transactionId = "points_abc123";

        when(producer.sendTransactionalPointsEvent("user_123", 100, "bonus"))
                .thenReturn(transactionId);
        when(valueOperations.get("POINTS:TX:RESULT:" + transactionId))
                .thenReturn(null);
        when(valueOperations.get("POINTS:TX:STATUS:" + transactionId))
                .thenReturn("ROLLBACK");

        // When & Then
        assertThatThrownBy(() -> pointsService.addPoints(request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void should_returnPoints_when_cacheHit() {
        // Given
        when(cacheService.getUserPoints("user_123")).thenReturn(500L);

        // When
        PointsResponse response = pointsService.getPoints("user_123");

        // Then
        assertThat(response.getTotalPoints()).isEqualTo(500L);
        verify(userPointsRepository, never()).findByUserId(anyString());
    }

    @Test
    void should_queryDbAndUpdateCache_when_cacheMiss() {
        // Given
        UserPoints userPoints = new UserPoints("user_123", 500L);
        when(cacheService.getUserPoints("user_123")).thenReturn(null);
        when(cacheService.hasKey("user_123")).thenReturn(false);
        when(redisLock.lock(RedisLock.LockType.CACHE_LOAD, "user_123")).thenReturn(true);
        when(userPointsRepository.findByUserId("user_123")).thenReturn(Optional.of(userPoints));

        // When
        PointsResponse response = pointsService.getPoints("user_123");

        // Then
        assertThat(response.getTotalPoints()).isEqualTo(500L);
        verify(cacheService).setUserPoints("user_123", 500L);
        verify(redisLock).unlock(RedisLock.LockType.CACHE_LOAD, "user_123");
    }

    @Test
    void should_throwException_when_userNotFound() {
        // Given
        when(cacheService.getUserPoints("unknown")).thenReturn(null);
        when(cacheService.hasKey("unknown")).thenReturn(false);
        when(redisLock.lock(RedisLock.LockType.CACHE_LOAD, "unknown")).thenReturn(true);
        when(userPointsRepository.findByUserId("unknown")).thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> pointsService.getPoints("unknown"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    @Test
    void should_updateReason_when_recordExists() {
        // Given
        PointRecord record = new PointRecord("user_123", 100, "old_reason");
        record.setId(1L);
        UpdateReasonRequest request = new UpdateReasonRequest("new_reason");

        when(pointRecordRepository.findById(1L)).thenReturn(Optional.of(record));
        when(pointRecordRepository.save(any(PointRecord.class))).thenReturn(record);

        // When
        PointRecord result = pointsService.updateReason(1L, request);

        // Then
        assertThat(result.getReason()).isEqualTo("new_reason");
    }

    @Test
    void should_throwException_when_recordNotFound() {
        // Given
        UpdateReasonRequest request = new UpdateReasonRequest("new_reason");
        when(pointRecordRepository.findById(999L)).thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> pointsService.updateReason(999L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.RECORD_NOT_FOUND);
    }

    @Test
    void should_deleteAllData_when_deleteUser() {
        // Given
        when(redisLock.lock(RedisLock.LockType.USER_POINTS, "user_123")).thenReturn(true);
        when(userPointsRepository.existsByUserId("user_123")).thenReturn(true);

        // When
        pointsService.deleteUserPoints("user_123");

        // Then
        verify(pointRecordRepository).deleteByUserId("user_123");
        verify(userPointsRepository).deleteById("user_123");
        verify(cacheService).deleteUserPoints("user_123");
        verify(cacheService).removeFromLeaderboard("user_123");
        verify(redisLock).unlock(RedisLock.LockType.USER_POINTS, "user_123");
    }
}

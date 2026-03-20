package com.example.demo.unit.mq;

import com.example.demo.exception.BusinessException;
import com.example.demo.lock.RedisLock;
import com.example.demo.model.dto.AddPointsTransactionArg;
import com.example.demo.model.entity.PointRecord;
import com.example.demo.model.entity.UserPoints;
import com.example.demo.mq.PointsTransactionListener;
import com.example.demo.repository.PointRecordRepository;
import com.example.demo.repository.UserPointsRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PointsTransactionListenerTest {

    @Mock
    private PointRecordRepository pointRecordRepository;

    @Mock
    private UserPointsRepository userPointsRepository;

    @Mock
    private RedisLock redisLock;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ObjectMapper objectMapper;

    private PointsTransactionListener listener;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        listener = new PointsTransactionListener(
                pointRecordRepository, userPointsRepository,
                redisLock, redisTemplate, objectMapper
        );
    }

    @Test
    void should_commitTransaction_when_newUserAddPoints() {
        // Given
        AddPointsTransactionArg txArg = new AddPointsTransactionArg(
                "points_123", "new_user", 100, "bonus"
        );
        Message<?> message = MessageBuilder.withPayload("test").build();

        when(redisLock.lock(RedisLock.LockType.USER_POINTS, "new_user")).thenReturn(true);
        when(userPointsRepository.findByUserId("new_user")).thenReturn(Optional.empty());
        when(userPointsRepository.save(any(UserPoints.class))).thenAnswer(inv -> inv.getArgument(0));
        when(pointRecordRepository.save(any(PointRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        RocketMQLocalTransactionState result = listener.executeLocalTransaction(message, txArg);

        // Then
        assertThat(result).isEqualTo(RocketMQLocalTransactionState.COMMIT);
        verify(userPointsRepository).save(any(UserPoints.class));
        verify(pointRecordRepository).save(any(PointRecord.class));
        verify(valueOperations).set(eq("POINTS:TX:STATUS:points_123"), contains("COMMIT"), anyLong(), any(TimeUnit.class));
        verify(redisLock).unlock(RedisLock.LockType.USER_POINTS, "new_user");
    }

    @Test
    void should_commitTransaction_when_existingUserAddPoints() {
        // Given
        AddPointsTransactionArg txArg = new AddPointsTransactionArg(
                "points_123", "user_123", 100, "bonus"
        );
        Message<?> message = MessageBuilder.withPayload("test").build();
        UserPoints existingPoints = new UserPoints("user_123", 500L);
        existingPoints.setVersion(0);

        when(redisLock.lock(RedisLock.LockType.USER_POINTS, "user_123")).thenReturn(true);
        when(userPointsRepository.findByUserId("user_123")).thenReturn(Optional.of(existingPoints));
        when(userPointsRepository.updatePointsWithOptimisticLock("user_123", 100, 0)).thenReturn(1);
        when(pointRecordRepository.save(any(PointRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        RocketMQLocalTransactionState result = listener.executeLocalTransaction(message, txArg);

        // Then
        assertThat(result).isEqualTo(RocketMQLocalTransactionState.COMMIT);
        verify(userPointsRepository).updatePointsWithOptimisticLock("user_123", 100, 0);
        verify(redisLock).unlock(RedisLock.LockType.USER_POINTS, "user_123");
    }

    @Test
    void should_rollbackTransaction_when_lockFailed() {
        // Given
        AddPointsTransactionArg txArg = new AddPointsTransactionArg(
                "points_123", "user_123", 100, "bonus"
        );
        Message<?> message = MessageBuilder.withPayload("test").build();

        when(redisLock.lock(RedisLock.LockType.USER_POINTS, "user_123")).thenReturn(false);

        // When
        RocketMQLocalTransactionState result = listener.executeLocalTransaction(message, txArg);

        // Then
        assertThat(result).isEqualTo(RocketMQLocalTransactionState.ROLLBACK);
        verify(userPointsRepository, never()).save(any());
        verify(pointRecordRepository, never()).save(any());
    }

    @Test
    void should_rollbackTransaction_when_optimisticLockFails() {
        // Given
        AddPointsTransactionArg txArg = new AddPointsTransactionArg(
                "points_123", "user_123", 100, "bonus"
        );
        Message<?> message = MessageBuilder.withPayload("test").build();
        UserPoints existingPoints = new UserPoints("user_123", 500L);
        existingPoints.setVersion(0);

        when(redisLock.lock(RedisLock.LockType.USER_POINTS, "user_123")).thenReturn(true);
        when(userPointsRepository.findByUserId("user_123")).thenReturn(Optional.of(existingPoints));
        when(userPointsRepository.updatePointsWithOptimisticLock("user_123", 100, 0)).thenReturn(0);

        // When
        RocketMQLocalTransactionState result = listener.executeLocalTransaction(message, txArg);

        // Then
        assertThat(result).isEqualTo(RocketMQLocalTransactionState.ROLLBACK);
        verify(redisLock).unlock(RedisLock.LockType.USER_POINTS, "user_123");
    }
}

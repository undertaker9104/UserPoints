package com.example.demo.unit.mq;

import com.example.demo.cache.PointsCacheService;
import com.example.demo.model.entity.UserPoints;
import com.example.demo.model.message.PointsEventMessage;
import com.example.demo.mq.PointsEventConsumer;
import com.example.demo.repository.UserPointsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PointsEventConsumerTest {

    @Mock
    private PointsCacheService cacheService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private UserPointsRepository userPointsRepository;

    private PointsEventConsumer consumer;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        consumer = new PointsEventConsumer(cacheService, redisTemplate, userPointsRepository);
    }

    @Test
    void should_updateCacheAndLeaderboard_when_addEvent() {
        // Given
        PointsEventMessage message = new PointsEventMessage(
                "points_123", "ADD", "user_123", 100, 500L, "bonus", LocalDateTime.now()
        );
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);

        // When
        consumer.onMessage(message);

        // Then
        verify(cacheService).setUserPoints("user_123", 500L);
        verify(cacheService).updateLeaderboard("user_123", 500L);
    }

    @Test
    void should_skip_when_eventAlreadyProcessed() {
        // Given
        PointsEventMessage message = new PointsEventMessage(
                "points_123", "ADD", "user_123", 100, 500L, "bonus", LocalDateTime.now()
        );
        when(redisTemplate.hasKey("POINTS:EVENT:PROCESSED:points_123")).thenReturn(true);

        // When
        consumer.onMessage(message);

        // Then
        verify(cacheService, never()).setUserPoints(anyString(), anyLong());
        verify(cacheService, never()).updateLeaderboard(anyString(), anyLong());
    }

    @Test
    void should_getTotalPointsFromRedis_when_transactionalMessage() {
        // Given - transactional message with totalPoints = 0
        PointsEventMessage message = new PointsEventMessage(
                "points_tx_123", "ADD", "user_123", 100, 0L, "bonus", LocalDateTime.now()
        );
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);
        when(valueOperations.get("POINTS:TX:RESULT:points_tx_123")).thenReturn("600");

        // When
        consumer.onMessage(message);

        // Then
        verify(cacheService).setUserPoints("user_123", 600L);
        verify(cacheService).updateLeaderboard("user_123", 600L);
    }

    @Test
    void should_getTotalPointsFromDb_when_redisResultMissing() {
        // Given - transactional message, Redis result missing, fall back to DB
        PointsEventMessage message = new PointsEventMessage(
                "points_tx_123", "ADD", "user_123", 100, 0L, "bonus", LocalDateTime.now()
        );
        UserPoints userPoints = new UserPoints("user_123", 700L);

        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);
        when(valueOperations.get("POINTS:TX:RESULT:points_tx_123")).thenReturn(null);
        when(userPointsRepository.findByUserId("user_123")).thenReturn(Optional.of(userPoints));

        // When
        consumer.onMessage(message);

        // Then
        verify(cacheService).setUserPoints("user_123", 700L);
        verify(cacheService).updateLeaderboard("user_123", 700L);
    }

    @Test
    void should_throwException_when_cannotDetermineTotalPoints() {
        // Given - transactional message, cannot determine totalPoints
        PointsEventMessage message = new PointsEventMessage(
                "points_tx_123", "ADD", "user_123", 100, 0L, "bonus", LocalDateTime.now()
        );

        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);
        when(valueOperations.get("POINTS:TX:RESULT:points_tx_123")).thenReturn(null);
        when(userPointsRepository.findByUserId("user_123")).thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> consumer.onMessage(message))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Cannot determine totalPoints");
    }
}

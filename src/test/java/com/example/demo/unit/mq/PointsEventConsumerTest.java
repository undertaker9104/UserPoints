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
        UserPoints userPoints = new UserPoints("user_123", 500L);

        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);
        when(userPointsRepository.findByUserId("user_123")).thenReturn(Optional.of(userPoints));

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
    void should_alwaysQueryDbForLatestTotalPoints() {
        // Given - even if message has totalPoints, consumer queries DB for latest value
        PointsEventMessage message = new PointsEventMessage(
                "points_tx_123", "ADD", "user_123", 100, 0L, "bonus", LocalDateTime.now()
        );
        // DB has the latest value (could be different from message due to concurrent updates)
        UserPoints userPoints = new UserPoints("user_123", 750L);

        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);
        when(userPointsRepository.findByUserId("user_123")).thenReturn(Optional.of(userPoints));

        // When
        consumer.onMessage(message);

        // Then - uses DB value (750), not message value
        verify(cacheService).setUserPoints("user_123", 750L);
        verify(cacheService).updateLeaderboard("user_123", 750L);
    }

    @Test
    void should_throwException_when_userNotFoundInDb() {
        // Given - user not found in DB
        PointsEventMessage message = new PointsEventMessage(
                "points_tx_123", "ADD", "user_123", 100, 0L, "bonus", LocalDateTime.now()
        );

        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);
        when(userPointsRepository.findByUserId("user_123")).thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> consumer.onMessage(message))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("User not found in DB");
    }
}

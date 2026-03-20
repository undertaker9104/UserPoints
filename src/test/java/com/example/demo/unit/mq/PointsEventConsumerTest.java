package com.example.demo.unit.mq;

import com.example.demo.cache.PointsCacheService;
import com.example.demo.model.message.PointsEventMessage;
import com.example.demo.mq.PointsEventConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

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

    private PointsEventConsumer consumer;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        consumer = new PointsEventConsumer(cacheService, redisTemplate);
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
}

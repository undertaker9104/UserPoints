package com.example.demo.unit.cache;

import com.example.demo.cache.PointsCacheService;
import com.example.demo.model.dto.LeaderboardEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PointsCacheServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    private PointsCacheService cacheService;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        cacheService = new PointsCacheService(redisTemplate);
    }

    @Test
    void should_returnPoints_when_cacheHit() {
        // Given
        when(valueOperations.get("user:points:user_123")).thenReturn("500");

        // When
        Long result = cacheService.getUserPoints("user_123");

        // Then
        assertThat(result).isEqualTo(500L);
    }

    @Test
    void should_returnNull_when_cacheMiss() {
        // Given
        when(valueOperations.get("user:points:user_123")).thenReturn(null);

        // When
        Long result = cacheService.getUserPoints("user_123");

        // Then
        assertThat(result).isNull();
    }

    @Test
    void should_returnNull_when_cacheIsNullMarker() {
        // Given
        when(valueOperations.get("user:points:user_123")).thenReturn("NULL");

        // When
        Long result = cacheService.getUserPoints("user_123");

        // Then
        assertThat(result).isNull();
    }

    @Test
    void should_setCache_when_setUserPoints() {
        // When
        cacheService.setUserPoints("user_123", 500L);

        // Then
        verify(valueOperations).set(
                eq("user:points:user_123"),
                eq("500"),
                eq(10L),
                eq(TimeUnit.MINUTES)
        );
    }

    @Test
    void should_updateLeaderboard_when_updateLeaderboard() {
        // When
        cacheService.updateLeaderboard("user_123", 500L);

        // Then
        verify(zSetOperations).add("leaderboard", "user_123", 500.0);
    }

    @Test
    void should_returnTopUsers_when_getLeaderboard() {
        // Given
        Set<ZSetOperations.TypedTuple<String>> tuples = Set.of(
                ZSetOperations.TypedTuple.of("user_001", 950.0),
                ZSetOperations.TypedTuple.of("user_002", 820.0)
        );
        when(zSetOperations.reverseRangeWithScores("leaderboard", 0, 9)).thenReturn(tuples);

        // When
        List<LeaderboardEntry> result = cacheService.getLeaderboard();

        // Then
        assertThat(result).hasSize(2);
    }
}

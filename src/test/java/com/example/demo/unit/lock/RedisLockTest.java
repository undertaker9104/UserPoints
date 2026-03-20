package com.example.demo.unit.lock;

import com.example.demo.lock.RedisLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisLockTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private RedisLock redisLock;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        redisLock = new RedisLock(redisTemplate);
    }

    @Test
    void should_returnTrue_when_lockSuccess() {
        // Given
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);

        // When
        boolean result = redisLock.lock(RedisLock.LockType.USER_POINTS, "user_123");

        // Then
        assertThat(result).isTrue();
        verify(valueOperations).setIfAbsent(
                eq("POINTS:LOCK:USER_POINTS:user_123"),
                eq("1"),
                eq(30L),
                eq(TimeUnit.SECONDS)
        );
    }

    @Test
    void should_returnFalse_when_lockAlreadyHeld() {
        // Given
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(false);

        // When
        boolean result = redisLock.lock(RedisLock.LockType.USER_POINTS, "user_123");

        // Then
        assertThat(result).isFalse();
    }

    @Test
    void should_releaseLock_when_unlock() {
        // Given
        when(redisTemplate.delete(anyString())).thenReturn(true);

        // When
        redisLock.unlock(RedisLock.LockType.USER_POINTS, "user_123");

        // Then
        verify(redisTemplate).delete("POINTS:LOCK:USER_POINTS:user_123");
    }

    @Test
    void should_useDifferentTTL_when_cacheLoadLock() {
        // Given
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);

        // When
        redisLock.lock(RedisLock.LockType.CACHE_LOAD, "user_123");

        // Then
        verify(valueOperations).setIfAbsent(
                eq("POINTS:LOCK:CACHE_LOAD:user_123"),
                eq("1"),
                eq(10L),
                eq(TimeUnit.SECONDS)
        );
    }
}

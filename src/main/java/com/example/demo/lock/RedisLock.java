package com.example.demo.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisLock {

    private static final String LOCK_KEY_PREFIX = "POINTS:LOCK:%s:%s";
    private static final String LOCK_VALUE = "1";

    private final StringRedisTemplate redisTemplate;

    public enum LockType {
        USER_POINTS(30),
        CACHE_LOAD(10);

        private final int expireSeconds;

        LockType(int expireSeconds) {
            this.expireSeconds = expireSeconds;
        }

        public int getExpireSeconds() {
            return expireSeconds;
        }
    }

    public boolean lock(LockType lockType, String key) {
        String lockKey = String.format(LOCK_KEY_PREFIX, lockType.name(), key);
        Boolean result = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, LOCK_VALUE, lockType.getExpireSeconds(), TimeUnit.SECONDS);
        boolean locked = Boolean.TRUE.equals(result);
        if (locked) {
            log.debug("Lock acquired: {}", lockKey);
        } else {
            log.debug("Lock failed: {}", lockKey);
        }
        return locked;
    }

    public void unlock(LockType lockType, String key) {
        String lockKey = String.format(LOCK_KEY_PREFIX, lockType.name(), key);
        redisTemplate.delete(lockKey);
        log.debug("Lock released: {}", lockKey);
    }
}

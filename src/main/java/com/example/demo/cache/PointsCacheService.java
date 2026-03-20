package com.example.demo.cache;

import com.example.demo.model.dto.LeaderboardEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class PointsCacheService {

    private static final String USER_POINTS_KEY = "user:points:%s";
    private static final String LEADERBOARD_KEY = "leaderboard";
    private static final String NULL_MARKER = "NULL";
    private static final long CACHE_TTL_MINUTES = 10;
    private static final long NULL_CACHE_TTL_MINUTES = 1;

    private final StringRedisTemplate redisTemplate;

    public Long getUserPoints(String userId) {
        String key = String.format(USER_POINTS_KEY, userId);
        String value = redisTemplate.opsForValue().get(key);

        if (value == null) {
            return null;
        }
        if (NULL_MARKER.equals(value)) {
            return null;
        }
        return Long.parseLong(value);
    }

    public void setUserPoints(String userId, Long points) {
        String key = String.format(USER_POINTS_KEY, userId);
        redisTemplate.opsForValue().set(key, String.valueOf(points), CACHE_TTL_MINUTES, TimeUnit.MINUTES);
        log.debug("Cache set: {} = {}", key, points);
    }

    public void setUserPointsNull(String userId) {
        String key = String.format(USER_POINTS_KEY, userId);
        redisTemplate.opsForValue().set(key, NULL_MARKER, NULL_CACHE_TTL_MINUTES, TimeUnit.MINUTES);
        log.debug("Cache set null marker: {}", key);
    }

    public void deleteUserPoints(String userId) {
        String key = String.format(USER_POINTS_KEY, userId);
        redisTemplate.delete(key);
        log.debug("Cache deleted: {}", key);
    }

    public void updateLeaderboard(String userId, Long points) {
        redisTemplate.opsForZSet().add(LEADERBOARD_KEY, userId, points.doubleValue());
        log.debug("Leaderboard updated: {} = {}", userId, points);
    }

    public void removeFromLeaderboard(String userId) {
        redisTemplate.opsForZSet().remove(LEADERBOARD_KEY, userId);
        log.debug("Removed from leaderboard: {}", userId);
    }

    public List<LeaderboardEntry> getLeaderboard() {
        Set<ZSetOperations.TypedTuple<String>> tuples =
                redisTemplate.opsForZSet().reverseRangeWithScores(LEADERBOARD_KEY, 0, 9);

        if (tuples == null || tuples.isEmpty()) {
            return Collections.emptyList();
        }

        List<LeaderboardEntry> entries = new ArrayList<>();
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            if (tuple.getValue() != null && tuple.getScore() != null) {
                entries.add(new LeaderboardEntry(tuple.getValue(), tuple.getScore().longValue()));
            }
        }

        // Sort by score descending
        entries.sort((a, b) -> Long.compare(b.getTotal(), a.getTotal()));
        return entries;
    }

    public boolean hasKey(String userId) {
        String key = String.format(USER_POINTS_KEY, userId);
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }
}

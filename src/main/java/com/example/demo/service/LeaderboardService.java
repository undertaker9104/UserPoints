package com.example.demo.service;

import com.example.demo.cache.PointsCacheService;
import com.example.demo.model.dto.LeaderboardEntry;
import com.example.demo.model.entity.UserPoints;
import com.example.demo.repository.UserPointsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LeaderboardService {

    private final PointsCacheService cacheService;
    private final UserPointsRepository userPointsRepository;

    public List<LeaderboardEntry> getLeaderboard() {
        // Step 1: Try to get from Redis
        List<LeaderboardEntry> leaderboard = cacheService.getLeaderboard();

        if (!leaderboard.isEmpty()) {
            return leaderboard;
        }

        // Step 2: Rebuild from MySQL (only fetch top 10 for performance)
        log.info("Leaderboard cache empty, rebuilding from database");
        List<UserPoints> topUsers = userPointsRepository.findTop10ByOrderByTotalPointsDesc();

        // Update Redis and return
        for (UserPoints userPoints : topUsers) {
            cacheService.updateLeaderboard(userPoints.getUserId(), userPoints.getTotalPoints());
        }

        return topUsers.stream()
                .map(u -> new LeaderboardEntry(u.getUserId(), u.getTotalPoints()))
                .collect(Collectors.toList());
    }
}

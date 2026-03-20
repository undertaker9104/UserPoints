package com.example.demo.controller;

import com.example.demo.model.dto.*;
import com.example.demo.model.entity.PointRecord;
import com.example.demo.service.LeaderboardService;
import com.example.demo.service.PointsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/points")
@RequiredArgsConstructor
public class PointsController {

    private final PointsService pointsService;
    private final LeaderboardService leaderboardService;

    @PostMapping
    public ApiResponse<PointsResponse> addPoints(@Valid @RequestBody AddPointsRequest request) {
        log.info("POST /points - userId={}, amount={}", request.getUserId(), request.getAmount());
        PointsResponse response = pointsService.addPoints(request);
        return ApiResponse.success(response);
    }

    @GetMapping("/{userId}")
    public ApiResponse<PointsResponse> getPoints(@PathVariable String userId) {
        log.info("GET /points/{}", userId);
        PointsResponse response = pointsService.getPoints(userId);
        return ApiResponse.success(response);
    }

    @GetMapping("/leaderboard")
    public ApiResponse<List<LeaderboardEntry>> getLeaderboard() {
        log.info("GET /points/leaderboard");
        List<LeaderboardEntry> leaderboard = leaderboardService.getLeaderboard();
        return ApiResponse.success(leaderboard);
    }

    @PutMapping("/{id}")
    public ApiResponse<PointRecord> updateReason(
            @PathVariable Long id,
            @Valid @RequestBody UpdateReasonRequest request) {
        log.info("PUT /points/{} - reason={}", id, request.getReason());
        PointRecord record = pointsService.updateReason(id, request);
        return ApiResponse.success(record);
    }

    @DeleteMapping("/{userId}")
    public ApiResponse<Void> deleteUserPoints(@PathVariable String userId) {
        log.info("DELETE /points/{}", userId);
        pointsService.deleteUserPoints(userId);
        return ApiResponse.success();
    }
}

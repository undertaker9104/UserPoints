package com.example.demo.integration.controller;

import com.example.demo.controller.PointsController;
import com.example.demo.exception.BusinessException;
import com.example.demo.exception.ErrorCode;
import com.example.demo.exception.GlobalExceptionHandler;
import com.example.demo.model.dto.LeaderboardEntry;
import com.example.demo.model.dto.PointsResponse;
import com.example.demo.model.entity.PointRecord;
import com.example.demo.service.LeaderboardService;
import com.example.demo.service.PointsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PointsController.class)
@Import(GlobalExceptionHandler.class)
class PointsControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PointsService pointsService;

    @MockBean
    private LeaderboardService leaderboardService;

    @Test
    void should_return200_when_addPointsSuccess() throws Exception {
        // Given
        PointsResponse response = new PointsResponse("user_123", 600L);
        when(pointsService.addPoints(any())).thenReturn(response);

        // When & Then
        mockMvc.perform(post("/points")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                                "userId": "user_123",
                                "amount": 100,
                                "reason": "bonus"
                            }
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.userId").value("user_123"))
                .andExpect(jsonPath("$.data.totalPoints").value(600));
    }

    @Test
    void should_return400_when_invalidRequest() throws Exception {
        mockMvc.perform(post("/points")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                                "userId": "",
                                "amount": -100
                            }
                            """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void should_return200_when_getUserPoints() throws Exception {
        // Given
        PointsResponse response = new PointsResponse("user_123", 500L);
        when(pointsService.getPoints("user_123")).thenReturn(response);

        // When & Then
        mockMvc.perform(get("/points/user_123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.totalPoints").value(500));
    }

    @Test
    void should_return404_when_userNotFound() throws Exception {
        // Given
        when(pointsService.getPoints("unknown"))
                .thenThrow(new BusinessException(ErrorCode.USER_NOT_FOUND));

        // When & Then
        mockMvc.perform(get("/points/unknown"))
                .andExpect(status().isNotFound());
    }

    @Test
    void should_return200_when_getLeaderboard() throws Exception {
        // Given
        List<LeaderboardEntry> entries = List.of(
                new LeaderboardEntry("user_001", 950L),
                new LeaderboardEntry("user_002", 820L)
        );
        when(leaderboardService.getLeaderboard()).thenReturn(entries);

        // When & Then
        mockMvc.perform(get("/points/leaderboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].userId").value("user_001"))
                .andExpect(jsonPath("$.data[0].total").value(950));
    }

    @Test
    void should_return200_when_updateReason() throws Exception {
        // Given
        PointRecord record = new PointRecord("user_123", 100, "new_reason");
        record.setId(1L);
        when(pointsService.updateReason(eq(1L), any())).thenReturn(record);

        // When & Then
        mockMvc.perform(put("/points/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                                "reason": "new_reason"
                            }
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void should_return404_when_recordNotFound() throws Exception {
        // Given
        when(pointsService.updateReason(eq(999L), any()))
                .thenThrow(new BusinessException(ErrorCode.RECORD_NOT_FOUND));

        // When & Then
        mockMvc.perform(put("/points/999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                                "reason": "new_reason"
                            }
                            """))
                .andExpect(status().isNotFound());
    }

    @Test
    void should_return200_when_deleteUser() throws Exception {
        // When & Then
        mockMvc.perform(delete("/points/user_123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}

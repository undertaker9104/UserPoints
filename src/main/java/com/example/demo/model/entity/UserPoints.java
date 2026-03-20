package com.example.demo.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_points")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserPoints {

    @Id
    @Column(name = "user_id", length = 100)
    private String userId;

    @Column(name = "total_points", nullable = false)
    private Long totalPoints = 0L;

    @Version
    @Column(nullable = false)
    private Integer version = 0;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public UserPoints(String userId, Long totalPoints) {
        this.userId = userId;
        this.totalPoints = totalPoints;
        this.version = 0;
    }
}

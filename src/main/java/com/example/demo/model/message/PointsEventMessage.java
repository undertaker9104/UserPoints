package com.example.demo.model.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PointsEventMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private String eventId;
    private String eventType;  // ADD
    private String userId;
    private Integer amount;
    private Long totalPoints;
    private String reason;
    private LocalDateTime timestamp;
}

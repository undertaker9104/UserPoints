package com.example.demo.model.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AddPointsRequest {

    @NotBlank(message = "userId cannot be blank")
    private String userId;

    @NotNull(message = "amount cannot be null")
    @Min(value = 1, message = "amount must be greater than 0")
    private Integer amount;

    private String reason;
}

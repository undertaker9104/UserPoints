package com.example.demo.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Transaction message argument object.
 * Used to pass local transaction parameters in RocketMQ transactional messages.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AddPointsTransactionArg implements Serializable {

    private static final long serialVersionUID = 1L;

    private String transactionId;
    private String userId;
    private Integer amount;
    private String reason;
}

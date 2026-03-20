-- User Points Service Database Schema
-- This script is executed automatically when MySQL container starts

USE taskdb;

-- Point Records Table: stores individual point transactions
CREATE TABLE IF NOT EXISTS point_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(100) NOT NULL,
    transaction_id VARCHAR(100),
    amount INT NOT NULL,
    reason VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id),
    INDEX idx_user_created (user_id, created_at DESC),
    INDEX idx_transaction_id (transaction_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- User Total Points Table: denormalized for performance
CREATE TABLE IF NOT EXISTS user_points (
    user_id VARCHAR(100) PRIMARY KEY,
    total_points BIGINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

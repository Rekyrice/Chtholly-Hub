-- Agent 执行 trace 持久化与失败模式挖掘

CREATE TABLE execution_traces (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    correlation_id   VARCHAR(64) NOT NULL,
    user_id          BIGINT NULL,
    session_id       VARCHAR(128) NULL,
    started_at       DATETIME(3) NOT NULL,
    finished_at      DATETIME(3) NULL,
    duration_ms      INT NULL,
    status           ENUM('SUCCESS', 'FAILURE', 'TIMEOUT', 'ABORTED') NOT NULL,
    steps_count      INT NOT NULL DEFAULT 0,
    tool_calls       JSON NULL,
    error_message    TEXT NULL,
    input_tokens     INT NOT NULL DEFAULT 0,
    output_tokens    INT NOT NULL DEFAULT 0,
    trace_payload    JSON NOT NULL,
    pattern_analyzed TINYINT(1) NOT NULL DEFAULT 0,
    created_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_correlation_id (correlation_id),
    INDEX idx_user_id (user_id),
    INDEX idx_status (status),
    INDEX idx_started_at (started_at),
    INDEX idx_pattern_analyzed (pattern_analyzed, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE trace_failure_patterns (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    pattern_key      VARCHAR(256) NOT NULL,
    occurrence_count INT NOT NULL DEFAULT 1,
    last_seen_at     DATETIME(3) NOT NULL,
    sample_trace_ids JSON NULL,
    resolution_hint  TEXT NULL,
    created_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_pattern (pattern_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

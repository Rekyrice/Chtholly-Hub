-- Kafka 消费失败死信记录表
CREATE TABLE IF NOT EXISTS dead_letter_messages (
    id BIGINT UNSIGNED NOT NULL,
    source_topic VARCHAR(100) NOT NULL,
    message_key VARCHAR(255) NULL,
    message_value TEXT NOT NULL,
    exception_class VARCHAR(255) NULL,
    exception_message TEXT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    status ENUM('PENDING', 'RETRYING', 'DEAD') NOT NULL DEFAULT 'PENDING',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_topic_status (source_topic, status),
    KEY idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

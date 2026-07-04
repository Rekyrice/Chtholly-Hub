CREATE TABLE IF NOT EXISTS archived_experiences (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  text TEXT NOT NULL,
  importance INT NOT NULL,
  source VARCHAR(64) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  archived_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_archived_experiences_created_at (created_at),
  KEY idx_archived_experiences_importance (importance)
);

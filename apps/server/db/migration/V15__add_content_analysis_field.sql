ALTER TABLE posts
  ADD COLUMN content_analysis JSON NULL COMMENT 'Agent content understanding result',
  ADD COLUMN content_analyzed_at DATETIME(3) NULL COMMENT 'Last Agent content understanding time';

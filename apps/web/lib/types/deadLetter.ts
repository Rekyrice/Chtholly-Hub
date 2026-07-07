export interface DeadLetterMessage {
  id: string;
  topic: string;
  sourceTopic?: string;
  messageKey?: string;
  payload?: string;
  failureReason: string;
  exceptionClass?: string;
  exceptionMessage?: string;
  retryCount: number;
  status?: string;
  createdAt: string;
  lastRetryAt?: string;
}

export interface DeadLetterPageResponse {
  items: DeadLetterMessage[];
  total: number;
  page: number;
  size: number;
  hasMore: boolean;
}

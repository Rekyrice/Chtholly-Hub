package com.chtholly.llm.service;

/**
 * 帖子摘要生成接口。
 */
public interface PostDescriptionService {

    String generateDescription(String content);
}
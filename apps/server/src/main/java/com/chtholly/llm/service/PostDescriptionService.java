package com.chtholly.llm.service;

/**
 * 知文摘要生成接口。
 */
public interface PostDescriptionService {

    String generateDescription(String content);
}
package com.chtholly.post.api.dto;

/**
 * 创建草稿响应：返回新建的帖子 ID（字符串避免前端精度丢失）。
 */
public record PostDraftCreateResponse(String id) {

}
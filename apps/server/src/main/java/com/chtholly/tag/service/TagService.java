package com.chtholly.tag.service;

import com.chtholly.tag.api.dto.TagResponse;

import java.util.List;

/** 标签业务：列表查询与帖子标签计数同步。 */
public interface TagService {

    List<TagResponse> listTags(int limit);

    /** 已发布帖子标签变更：对比 old/new 调整 usage_count 并 upsert 标签行。 */
    void syncPublishedPostTags(long creatorId, List<String> oldTags, List<String> newTags);

    /** 已发布帖子删除：递减各标签 usage_count。 */
    void releasePublishedPostTags(List<String> tags);
}

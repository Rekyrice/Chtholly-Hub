package com.chtholly.bangumi.mapper;

import com.chtholly.bangumi.model.BangumiSubjectRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface BangumiSubjectMapper {

    List<BangumiSubjectRow> searchByKeyword(@Param("keyword") String keyword, @Param("limit") int limit);

    /** 中文标题 FULLTEXT 常无命中时的 LIKE 回退 */
    List<BangumiSubjectRow> searchByKeywordLike(@Param("keyword") String keyword, @Param("limit") int limit);

    BangumiSubjectRow findById(@Param("id") long id);

    void upsert(BangumiSubjectRow row);

    void insertSyncLog(@Param("subjectId") long subjectId, @Param("action") String action);
}

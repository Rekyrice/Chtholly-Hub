package com.chtholly.bangumi.service;

import com.chtholly.bangumi.model.BangumiSubjectRow;

import java.util.List;

public interface BangumiService {

    /** Agent 工具用：先查本地 FULLTEXT，miss 则调 Bangumi API 并回填。 */
    List<BangumiSubjectRow> search(String keyword, int limit);
}

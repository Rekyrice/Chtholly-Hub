package com.chtholly.bangumi.service;

import com.chtholly.bangumi.model.BangumiSubjectRow;

import java.util.List;

public interface BangumiService {

    /** Agent 工具用：先查本地 FULLTEXT，miss 则调 Bangumi API 并回填。 */
    List<BangumiSubjectRow> search(String keyword, int limit);

    /**
     * 查同一系列的全部动画条目（多季/OVA），始终走 Bangumi API 宽召回，避免本地只缓存一季。
     */
    List<BangumiSubjectRow> searchAnimeSeries(String keyword, int limit);

    /**
     * 查询 Bangumi 人物及其参与作品。
     *
     * @param keyword       人物名/笔名，或作品名（配合 workTitleHint）
     * @param workTitleHint 若问题涉及「某作品的作者」，传入作品名以便反查
     * @param workType      过滤作品类型：book / anime / all
     */
    String describePersonWorks(String keyword, String workTitleHint, String workType);
}

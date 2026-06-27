package com.chtholly.agent.tools;

import com.chtholly.agent.AgentTool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Bangumi 番剧搜索占位（M2-4 接入后替换实现）。 */
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class BangumiSearchTool implements AgentTool {

    @Override
    public String name() {
        return "bangumi_search";
    }

    @Override
    public String description() {
        return "搜索 Bangumi 番剧元数据（集数、Staff 等）。input: {\"keyword\":\"番剧名\"}";
    }

    @Override
    public String execute(Map<String, Object> input, long userId) {
        String keyword = input.get("keyword") == null ? "" : String.valueOf(input.get("keyword")).trim();
        if (keyword.isEmpty()) {
            return "错误：缺少参数 keyword";
        }
        return "Bangumi 数据库尚未接入（计划 M2-4）。当前无法查询「" + keyword + "」。"
                + "请改用 fulltext_search 搜索站内文章，或 article_rag 做语义检索。";
    }
}

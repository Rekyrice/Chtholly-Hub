package com.chtholly.agent.tools;

import com.chtholly.agent.AgentTool;
import com.chtholly.agent.memory.AgentContextUtil;
import com.chtholly.bangumi.service.BangumiService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bangumi 条目角色查询：主要人物、宿舍伙伴等登场角色。 */
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
@RequiredArgsConstructor
public class BangumiCharactersTool implements AgentTool {

    private final BangumiService bangumiService;

    @Override
    public String name() {
        return "bangumi_characters";
    }

    @Override
    public String description() {
        return """
                查询 Bangumi 条目的登场角色列表（主役/配角等）。
                适用于「主要人物有哪些」「宿舍伙伴是谁」等角色类问题；追问时需结合对话历史传入作品 keyword。
                input: {"keyword":"条目名或系列简称"}""";
    }

    @Override
    public String execute(Map<String, Object> input, long userId) {
        List<String> keywords = buildKeywordCandidates(input);
        if (keywords.isEmpty()) {
            return "错误：缺少参数 keyword（可从对话历史中的作品名推断）";
        }

        IllegalStateException lastApiError = null;
        for (String keyword : keywords) {
            try {
                String result = bangumiService.describeSubjectCharacters(keyword);
                if (result != null && !result.contains("未找到") && !result.contains("暂无")) {
                    return result;
                }
            } catch (IllegalStateException e) {
                lastApiError = e;
            }
        }

        if (lastApiError != null) {
            return lastApiError.getMessage();
        }
        return "Bangumi 未找到与「" + keywords.get(0) + "」相关的角色信息。";
    }

    private List<String> buildKeywordCandidates(Map<String, Object> input) {
        Set<String> candidates = new LinkedHashSet<>();
        String keyword = input.get("keyword") == null ? "" : String.valueOf(input.get("keyword")).trim();
        if (StringUtils.hasText(keyword)) {
            candidates.add(keyword);
        }
        Object userQuestion = input.get("_userQuestion");
        Object history = input.get("_conversationHistory");
        String q = userQuestion == null ? "" : String.valueOf(userQuestion);
        String h = history == null ? "" : String.valueOf(history);
        for (String title : AgentContextUtil.extractWorkTitleCandidates(h, q)) {
            candidates.add(title);
        }
        return new ArrayList<>(candidates);
    }
}

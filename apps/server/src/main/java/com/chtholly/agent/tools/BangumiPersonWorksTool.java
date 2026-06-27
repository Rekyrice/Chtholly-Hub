package com.chtholly.agent.tools;

import com.chtholly.agent.AgentTool;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bangumi 人物作品查询：作者/漫画家参与的全部条目。 */
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
@RequiredArgsConstructor
public class BangumiPersonWorksTool implements AgentTool {

    private static final Pattern WORK_TITLE = Pattern.compile(
            "《([^》]+)》|「([^」]+)」|“([^”]+)”|\"([^\"]+)\"");

    private final BangumiService bangumiService;

    @Override
    public String name() {
        return "bangumi_person_works";
    }

    @Override
    public String description() {
        return """
                查询 Bangumi 人物（作者/漫画家/插画等）及其参与作品列表。
                适用于「某作者有哪些漫画」「某作品的作者还画过什么」。
                用户限定漫画/动画时请在 work_type 传 book 或 anime，否则传 all。
                input: {"keyword":"人名或笔名","work_title":"作品名(可选)","work_type":"book|anime|all"}""";
    }

    @Override
    public String execute(Map<String, Object> input, long userId) {
        String keyword = str(input.get("keyword"));
        String workTitle = str(input.get("work_title"));
        String workType = str(input.get("work_type"));
        if (!StringUtils.hasText(workType)) {
            workType = "all";
        }

        Object userQuestion = input.get("_userQuestion");
        if (userQuestion != null) {
            String q = String.valueOf(userQuestion);
            if (!StringUtils.hasText(workTitle)) {
                workTitle = extractWorkTitleFromQuestion(q);
            }
        }

        if (!StringUtils.hasText(keyword) && !StringUtils.hasText(workTitle)) {
            return "错误：缺少 keyword 或 work_title";
        }

        List<String[]> attempts = buildAttempts(keyword, workTitle);
        IllegalStateException lastApiError = null;
        for (String[] attempt : attempts) {
            try {
                String result = bangumiService.describePersonWorks(attempt[0], attempt[1], workType);
                if (result != null && !result.contains("未找到")) {
                    return result;
                }
            } catch (IllegalStateException e) {
                lastApiError = e;
            }
        }

        if (lastApiError != null) {
            return lastApiError.getMessage();
        }
        return "Bangumi 未找到相关人物或作品列表。";
    }

    private String extractWorkTitleFromQuestion(String question) {
        Matcher m = WORK_TITLE.matcher(question);
        if (m.find()) {
            for (int i = 1; i <= m.groupCount(); i++) {
                if (StringUtils.hasText(m.group(i))) {
                    return m.group(i);
                }
            }
        }
        String q = question.trim().replaceAll("[？?。！!，,；;].*$", "");
        q = q.replaceAll("^(查找|搜索|查一下|帮我查|查询|请问|告诉我|想知道)", "").trim();
        q = q.replaceAll("(的作者|作者一共有|作者有|一共有|有几|几部|多少|漫画作品|作品列表|作品|分别是什么).*$", "").trim();
        return q.length() >= 2 && q.length() <= 30 ? q : null;
    }

    private List<String[]> buildAttempts(String keyword, String workTitle) {
        Set<String> keywords = new LinkedHashSet<>();
        if (StringUtils.hasText(keyword)) {
            keywords.add(keyword.trim());
        }

        List<String[]> attempts = new ArrayList<>();
        for (String k : keywords) {
            attempts.add(new String[] { k, workTitle });
        }
        if (StringUtils.hasText(workTitle)) {
            attempts.add(new String[] { null, workTitle });
        }
        return attempts;
    }

    private String str(Object v) {
        return v == null ? null : String.valueOf(v).trim();
    }
}

package com.chtholly.comment.service;

import com.chtholly.comment.config.CommentProperties;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.regex.Pattern;

/** 评论内容 XSS 过滤与敏感词替换。 */
@Component
@RequiredArgsConstructor
public class CommentContentSanitizer {

    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");

    private final CommentProperties commentProperties;

    /** 去除 HTML、trim、敏感词替换。 */
    public String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        String noHtml = Jsoup.clean(trimmed, Safelist.none());
        if (!StringUtils.hasText(noHtml)) {
            noHtml = HTML_TAG.matcher(trimmed).replaceAll("").trim();
        }
        return applySensitiveWords(noHtml);
    }

    private String applySensitiveWords(String content) {
        String result = content;
        for (String word : commentProperties.getSensitiveWords()) {
            if (!StringUtils.hasText(word)) {
                continue;
            }
            result = result.replace(word, "***");
        }
        return result;
    }
}

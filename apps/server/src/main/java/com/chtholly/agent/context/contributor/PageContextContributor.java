package com.chtholly.agent.context.contributor;

import com.chtholly.agent.context.ContextContribution;
import com.chtholly.agent.context.ContextContributor;
import com.chtholly.agent.context.ContextOrder;
import com.chtholly.agent.context.ContextRequest;
import com.chtholly.content.ContentAnalysis;
import com.chtholly.content.ContentIntelligenceReader;
import com.chtholly.content.Entity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Renders client page context and optional stored analysis of the current post. */
@Slf4j
@Component
public class PageContextContributor implements ContextContributor {

    private final ContentIntelligenceReader contentReader;

    @Autowired
    public PageContextContributor(ObjectProvider<ContentIntelligenceReader> contentReaderProvider) {
        this(contentReaderProvider.getIfAvailable());
    }

    public PageContextContributor(ContentIntelligenceReader contentReader) {
        this.contentReader = contentReader;
    }

    @Override
    public String name() {
        return "page";
    }

    @Override
    public int order() {
        return ContextOrder.PAGE;
    }

    @Override
    public ContextContribution contribute(ContextRequest request) {
        StringBuilder prompt = new StringBuilder();
        appendPageContext(prompt, request.pageContext());
        boolean degraded = appendCurrentPostAnalysis(prompt, request.pageContext());
        return prompt.isEmpty()
                ? ContextContribution.empty(name(), order(), degraded)
                : new ContextContribution(name(), order(), prompt.toString(), degraded);
    }

    private void appendPageContext(StringBuilder prompt, String pageContext) {
        if (hasText(pageContext)) {
            prompt.append("## 用户当前在看\n\n").append(pageContext.trim());
        }
    }

    private boolean appendCurrentPostAnalysis(StringBuilder prompt, String pageContext) {
        if (contentReader == null) {
            return false;
        }
        try {
            ContentAnalysis analysis = loadCurrentPostAnalysis(pageContext);
            if (analysis == null) {
                return false;
            }
            if (!prompt.isEmpty()) {
                prompt.append("\n\n");
            }
            prompt.append("## 当前文章\n\n");
            if (hasText(analysis.summary())) {
                prompt.append("摘要：").append(analysis.summary().trim()).append('\n');
            }
            String entities = formatEntities(analysis.entities());
            if (hasText(entities)) {
                prompt.append("涉及：").append(entities).append('\n');
            }
            return false;
        } catch (RuntimeException e) {
            log.warn("Current post analysis context failed", e);
            return true;
        }
    }

    private ContentAnalysis loadCurrentPostAnalysis(String pageContext) {
        Long postId = extractCurrentPostId(pageContext);
        if (postId != null) {
            return contentReader.getAnalysis(postId);
        }
        String postSlug = extractCurrentPostSlug(pageContext);
        return hasText(postSlug) ? contentReader.getAnalysisBySlug(postSlug) : null;
    }

    static Long extractCurrentPostId(String pageContext) {
        if (!hasText(pageContext)) return null;
        Matcher matcher = matcher(pageContext, "(?i)postId\\s*[:：=]\\s*(\\d+)");
        if (matcher == null) matcher = matcher(pageContext, "(?i)post_id\\s*[:：=]\\s*(\\d+)");
        if (matcher == null) return null;
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String extractCurrentPostSlug(String pageContext) {
        if (!hasText(pageContext)) return null;
        Matcher matcher = matcher(pageContext, "(?i)postSlug\\s*[:：=]\\s*([^\\s\\n]+)");
        if (matcher != null) return cleanContextSlug(matcher.group(1));
        matcher = matcher(pageContext, "(?i)source\\s*[:：=]\\s*post:([^\\s\\n]+)");
        return matcher == null ? null : cleanContextSlug(matcher.group(1));
    }

    static String formatEntities(List<Entity> entities) {
        if (entities == null || entities.isEmpty()) return "";
        return entities.stream()
                .filter(entity -> entity != null && hasText(entity.name()))
                .map(entity -> entity.name().trim())
                .distinct()
                .collect(Collectors.joining("、"));
    }

    private static Matcher matcher(String text, String pattern) {
        Matcher matcher = Pattern.compile(pattern).matcher(text);
        return matcher.find() ? matcher : null;
    }

    private static String cleanContextSlug(String value) {
        if (value == null) return null;
        String cleaned = value.trim();
        int queryIndex = cleaned.indexOf('?');
        if (queryIndex >= 0) cleaned = cleaned.substring(0, queryIndex);
        return cleaned.isBlank() ? null : cleaned;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

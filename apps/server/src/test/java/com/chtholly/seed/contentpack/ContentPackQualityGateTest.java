package com.chtholly.seed.contentpack;

import com.chtholly.seed.contentpack.model.ContentPack;
import com.chtholly.seed.contentpack.model.ContentPackManifest;
import com.chtholly.seed.contentpack.model.SeedPostDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentPackQualityGateTest {

    private final ContentPackQualityGate gate = new ContentPackQualityGate();

    @Test
    void reportsBoilerplateDuplicateLeadHeadingSequenceAndEnding(@TempDir Path root) {
        String sharedLead = "在当今快速发展的时代，先看一段完全相同的开头。";
        String sharedEnd = "最后留下一段完全相同的收尾。";
        ContentPack pack = pack(root,
                post("a", "anchor-a", "## 观察\n\n" + sharedLead + "\n\n### 记录\n\n" + "甲".repeat(650) + "\n\n" + sharedEnd),
                post("b", "anchor-b", "## 观察\n\n" + sharedLead + "\n\n### 记录\n\n" + "乙".repeat(650) + "\n\n" + sharedEnd));

        ContentPackQualityGate.QualityGateResult result = gate.audit(pack);

        assertTrue(result.errors().stream().anyMatch(value -> value.contains("boilerplate") && value.contains("a")));
        assertTrue(result.errors().stream().anyMatch(value -> value.contains("duplicate lead") && value.contains("a") && value.contains("b")));
        assertTrue(result.errors().stream().anyMatch(value -> value.contains("identical heading sequence") && value.contains("a") && value.contains("b")));
        assertTrue(result.errors().stream().anyMatch(value -> value.contains("repeated ending") && value.contains("a") && value.contains("b")));
    }

    @Test
    void reportsFiveGramSimilarityAndMissingFactAnchor(@TempDir Path root) {
        String common = "这一段文字用于验证跨文章五元语法相似度能够稳定命中而不是依赖语言模型判断。".repeat(15);
        ContentPack pack = pack(root,
                post("a", "JVM 指标", common + "甲".repeat(300)),
                post("b", "数据库慢查询", common + "乙".repeat(300)));

        ContentPackQualityGate.QualityGateResult result = gate.audit(pack);

        assertTrue(result.errors().stream().anyMatch(value -> value.contains("5-gram similarity") && value.contains("a") && value.contains("b")));
        assertTrue(result.errors().stream().anyMatch(value -> value.contains("missing fact anchor") && value.contains("a")));
        assertTrue(result.errors().stream().anyMatch(value -> value.contains("missing fact anchor") && value.contains("b")));
    }

    @Test
    void treatsHeadingLevelAsPartOfSequenceAndFindsLeadAfterAdjacentHeading(@TempDir Path root) {
        String lead = "标题之后紧接着的第一段也应参与重复检查。";
        ContentPack pack = pack(root,
                post("a", "锚点", "## 同名标题\n" + lead + "\n\n锚点" + "甲".repeat(600)),
                post("b", "锚点", "### 同名标题\n" + lead + "\n\n锚点" + "乙".repeat(600)));

        ContentPackQualityGate.QualityGateResult result = gate.audit(pack);

        assertTrue(result.errors().stream().anyMatch(value -> value.contains("duplicate lead")));
        assertFalse(result.errors().stream().anyMatch(value -> value.contains("identical heading sequence")));
    }

    @Test
    void enforcesBodyLengthExceptForShortNotes(@TempDir Path root) {
        ContentPack pack = pack(root,
                post("short", "锚点", "只有锚点", "article"),
                post("long", "锚点", "锚点" + "长".repeat(2601), "article"),
                post("note", "锚点", "短记锚点", "short-note"));

        ContentPackQualityGate.QualityGateResult result = gate.audit(pack);

        assertTrue(result.errors().stream().anyMatch(value -> value.contains("body length") && value.contains("short")));
        assertTrue(result.errors().stream().anyMatch(value -> value.contains("body length") && value.contains("long")));
        assertFalse(result.errors().stream().anyMatch(value -> value.contains("body length") && value.contains("note")));
    }

    @Test
    void reportsEveryBannedPhraseInStablePostOrder(@TempDir Path root) {
        ContentPack pack = pack(root,
                post("a", "锚点", "锚点。首先分析，其次验证，最后复盘。总的来说，值得一提，让我们继续。" + "甲".repeat(600)));

        ContentPackQualityGate.QualityGateResult result = gate.audit(pack);

        assertTrue(result.errors().stream().anyMatch(value -> value.contains("boilerplate") && value.contains("首先.*其次.*最后")));
        assertTrue(result.errors().stream().anyMatch(value -> value.contains("总的来说")));
        assertTrue(result.errors().stream().anyMatch(value -> value.contains("值得一提")));
        assertTrue(result.errors().stream().anyMatch(value -> value.contains("让我们")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"```", "~~~"})
    void ignoresFencedCodeUrlsAndImageTargetsDuringCrossPostComparison(String fence, @TempDir Path root) {
        String sharedTechnicalBlock = fence + "java\n" + """
                ## 这不是 Markdown 标题
                String endpoint = "https://internal.example/api/v1/repeated/path";
                String payload = "%s";
                """.formatted("shared-code-token-".repeat(80))
                + fence + "\n\nhttps://internal.example/api/v1/repeated/path\n";
        String left = sharedTechnicalBlock
                + "\n## 甲的观察\n\n锚点甲，这才是第一段。" + "甲".repeat(620)
                + "\n\n甲自己的收尾。\n\n![相同示意图](https://cdn.example/same/path/diagram.png)";
        String right = sharedTechnicalBlock
                + "\n## 乙的观察\n\n锚点乙，这才是第一段。" + "乙".repeat(620)
                + "\n\n乙自己的收尾。\n\n![相同示意图](https://cdn.example/same/path/diagram.png)";
        ContentPack pack = pack(root, post("a", "锚点甲", left), post("b", "锚点乙", right));

        ContentPackQualityGate.QualityGateResult result = gate.audit(pack);

        assertFalse(result.errors().stream().anyMatch(value -> value.contains("duplicate lead")));
        assertFalse(result.errors().stream().anyMatch(value -> value.contains("repeated ending")));
        assertFalse(result.errors().stream().anyMatch(value -> value.contains("identical heading sequence")));
        assertFalse(result.errors().stream().anyMatch(value -> value.contains("5-gram similarity")));
    }

    @Test
    void preservesInlineCodeTextForFactAnchorsAndHeadings(@TempDir Path root) {
        String left = "## 用 `ReAct` 做复盘\n\n这次执行了 `EXPLAIN ANALYZE`。" + "甲".repeat(620);
        String right = "## 用 `Plan-and-Execute` 做复盘\n\n这次检查了 `Python Agent`。" + "乙".repeat(620);
        ContentPack pack = pack(root,
                post("a", "EXPLAIN ANALYZE", left),
                post("b", "Python Agent", right));

        ContentPackQualityGate.QualityGateResult result = gate.audit(pack);

        assertFalse(result.errors().stream().anyMatch(value -> value.contains("missing fact anchor")));
        assertFalse(result.errors().stream().anyMatch(value -> value.contains("identical heading sequence")));
    }

    @Test
    void stopsRawUrlAtChinesePunctuationWithoutWhitespace(@TempDir Path root) {
        String markdown = "详见 https://a.test。锚点在这里" + "文".repeat(600);
        ContentPack pack = pack(root, post("a", "锚点在这里", markdown));

        ContentPackQualityGate.QualityGateResult result = gate.audit(pack);

        assertFalse(result.errors().stream().anyMatch(value -> value.contains("missing fact anchor")));
        assertFalse(result.errors().stream().anyMatch(value -> value.contains("body length")));
    }

    private ContentPack pack(Path root, SeedPostDefinition... posts) {
        return new ContentPack(root,
                new ContentPackManifest("v", "namespace", "review", 0, posts.length, Map.of("TECH", posts.length)),
                List.of(), Map.of(), List.of(posts), List.of(), List.of(), List.of(), List.of());
    }

    private SeedPostDefinition post(String key, String anchor, String markdown) {
        return post(key, anchor, markdown, "article");
    }

    private SeedPostDefinition post(String key, String anchor, String markdown, String format) {
        return new SeedPostDefinition(key, null, "author", key, key, "足够长度的文章描述", "TECH", List.of(),
                Instant.parse("2026-01-01T00:00:00Z"), "markdown/" + key + ".md", "cover", List.of(),
                new SeedPostDefinition.ArticleBrief(List.of(anchor), "voice", "position", format, List.of(), List.of()), markdown);
    }
}

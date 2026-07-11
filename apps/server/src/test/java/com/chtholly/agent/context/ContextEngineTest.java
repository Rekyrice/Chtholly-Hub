package com.chtholly.agent.context;

import com.chtholly.agent.AgentTool;
import com.chtholly.agent.ParamDef;
import com.chtholly.agent.anchor.AnchorContext;
import com.chtholly.agent.anchor.AnchorManager;
import com.chtholly.agent.anchor.KnowledgeService;
import com.chtholly.agent.context.contributor.HistoryContextContributor;
import com.chtholly.agent.context.contributor.IdentityContextContributor;
import com.chtholly.agent.context.contributor.KnowledgeContextContributor;
import com.chtholly.agent.context.contributor.PageContextContributor;
import com.chtholly.agent.context.contributor.ProceduralContextContributor;
import com.chtholly.agent.context.contributor.QuestionContextContributor;
import com.chtholly.agent.context.contributor.RelationshipContextContributor;
import com.chtholly.agent.context.contributor.ToolsContextContributor;
import com.chtholly.agent.memory.AgentTurn;
import com.chtholly.content.ContentAnalysis;
import com.chtholly.content.ContentIntelligenceReader;
import com.chtholly.content.Entity;
import com.chtholly.agent.graph.KnowledgeGraphService;
import com.chtholly.agent.mood.SeasonService;
import com.chtholly.agent.search.HybridSearchService;
import com.chtholly.agent.search.SearchResult;
import com.chtholly.agent.state.BehaviorProb;
import com.chtholly.agent.state.CharacterState;
import com.chtholly.agent.state.CharacterStateService;
import com.chtholly.agent.state.EmotionState;
import com.chtholly.agent.state.Mood;
import com.chtholly.agent.state.Needs;
import com.chtholly.agent.state.Personality;
import com.chtholly.agent.state.Relationship;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContextEngineTest {

    private AnchorManager anchorManager;
    private CharacterStateService stateService;
    private SeasonService seasonService;
    private ContextEngine contextEngine;

    @BeforeEach
    void setUp() {
        anchorManager = mock(AnchorManager.class);
        stateService = mock(CharacterStateService.class);
        when(stateService.getMoodBaseline()).thenReturn(-0.1);
        when(stateService.getMoodValence()).thenReturn(0.3);
        when(stateService.getCurrentEmotion()).thenReturn(new EmotionState(
                "好奇",
                0.65,
                Instant.parse("2026-07-05T03:00:00Z"),
                "user-interaction"));
        when(anchorManager.buildContext(7L, "ws-1")).thenReturn(new AnchorContext(
                "# 珂朵莉\n认真到笨拙，但不会编造答案。",
                List.of(AgentTurn.user("anchor history")),
                List.of("站内有一篇关于芙莉莲时间主题的文章"),
                List.of("用户喜欢简洁回答"),
                defaultState()));
        seasonService = mock(SeasonService.class);
        when(seasonService.getSeasonalPrompt()).thenReturn("秋天了……适合感性一点的故事，关于离别和怀念的。");
        contextEngine = fullEngine(null, null, null, seasonService, null);
    }

    @Test
    void injectsSlowMoodAndFastEmotionIntoCurrentStateLayer() {
        when(stateService.getMoodValence()).thenReturn(-0.35);
        when(stateService.getCurrentEmotion()).thenReturn(new EmotionState(
                "感伤",
                0.5,
                Instant.parse("2026-07-05T03:00:00Z"),
                "user-interaction"));

        String prompt = contextEngine.buildSystemPrompt(
                7L,
                "ws-1",
                "",
                List.of(),
                "",
                "继续聊聊吧");

        assertThat(prompt)
                .contains("- 心情：有点低落，说不上为什么（valence: -0.35）")
                .contains("- 情绪：有些感伤，但愿意继续聊（感伤, intensity: 0.50）");
    }

    @Test
    void injectsSeasonalFeelingAfterCurrentStateLayer() {
        String prompt = contextEngine.buildSystemPrompt(
                7L,
                "ws-1",
                "",
                List.of(),
                "",
                "推荐一点适合现在看的作品");

        assertThat(prompt)
                .contains("## 季节感受")
                .contains("秋天了……适合感性一点的故事，关于离别和怀念的。");
    }

    @Test
    void buildsLayeredPromptFromAnchorsWithToolsHistoryAndQuestion() {
        String prompt = contextEngine.buildSystemPrompt(
                7L,
                "ws-1",
                "页面：/post/frieren-review\n标题：《芙莉莲》观后感\n标签：芙莉莲、治愈",
                List.of(mockTool()),
                "User: 上一次的问题\nAssistant: 上一次的回答",
                "你怎么看这篇文章？");

        assertThat(prompt)
                .contains("## 你的身份", "# 珂朵莉")
                .contains("## 当前状态", "亲密度：熟悉", "熟悉的人", "互动次数：8")
                .contains("当前时间段：", "心境基线：-0.1")
                .contains("## 用户当前在看", "页面：/post/frieren-review", "标题：《芙莉莲》观后感")
                .contains("## 相关知识", "- 站内有一篇关于芙莉莲时间主题的文章")
                .contains("## 你学到的行为规则", "- 用户喜欢简洁回答")
                .contains("## 可用工具", "### test_tool", "测试工具")
                .contains("## 工具使用准则", "每次只调用一个工具")
                .contains("## 对话历史", "User: 上一次的问题")
                .contains("## 用户的问题", "你怎么看这篇文章？");
        assertThat(prompt).doesNotContain("[系统提示]");
        assertThat(prompt.length()).isLessThan(8_000);
        assertThat(prompt.indexOf("## 你的身份"))
                .isLessThan(prompt.indexOf("## 当前状态"));
        assertThat(prompt.indexOf("## 当前状态"))
                .isLessThan(prompt.indexOf("## 用户当前在看"));
        assertThat(prompt.indexOf("## 用户当前在看"))
                .isLessThan(prompt.indexOf("## 相关知识"));
        assertThat(prompt.indexOf("## 相关知识"))
                .isLessThan(prompt.indexOf("## 你学到的行为规则"));
        assertThat(prompt.indexOf("## 你学到的行为规则"))
                .isLessThan(prompt.indexOf("## 可用工具"));
        assertThat(prompt.indexOf("## 可用工具"))
                .isLessThan(prompt.indexOf("## 对话历史"));
        assertThat(prompt.indexOf("## 对话历史"))
                .isLessThan(prompt.indexOf("## 用户的问题"));
        verify(anchorManager).buildContext(7L, "ws-1");
    }

    @Test
    void usesEpisodicAnchorWhenFormattedHistoryIsBlank() {
        String prompt = contextEngine.buildSystemPrompt(
                7L,
                "ws-1",
                "",
                List.of(),
                "",
                "继续说");

        assertThat(prompt)
                .contains("## 对话历史")
                .contains("User: anchor history");
    }

    @Test
    void injectsHybridSearchResultsForQueryIntent() {
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        ContextEngine engine = engineWith(new KnowledgeContextContributor(hybridSearchService, null, null));
        when(hybridSearchService.hybridSearch("帮我查一下芙莉莲的时间主题", 5)).thenReturn(List.of(
                new SearchResult("post:9", "时间的重量", "芙莉莲文章片段", "hybrid", 0.2)
        ));

        String prompt = engine.buildSystemPrompt(
                7L,
                "ws-1",
                "",
                List.of(),
                "",
                "帮我查一下芙莉莲的时间主题");

        assertThat(prompt)
                .contains("## 相关知识")
                .contains("- 站内有一篇关于芙莉莲时间主题的文章")
                .contains("- 时间的重量：芙莉莲文章片段");
        verify(hybridSearchService).hybridSearch("帮我查一下芙莉莲的时间主题", 5);
    }

    @Test
    void skipsHybridSearchWhenQuestionHasNoQueryIntent() {
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        ContextEngine engine = engineWith(new KnowledgeContextContributor(hybridSearchService, null, null));

        engine.buildSystemPrompt(
                7L,
                "ws-1",
                "",
                List.of(),
                "",
                "嗯，继续说");

        verify(hybridSearchService, never()).hybridSearch(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void injectsKnowledgeBaseForAnimeQuestion() {
        KnowledgeService knowledgeService = mock(KnowledgeService.class);
        ContextEngine engine = engineWith(new KnowledgeContextContributor(null, knowledgeService, null));
        when(knowledgeService.searchRelevantKnowledge("聊聊芙莉莲关于时间的主题", 3)).thenReturn(List.of(
                "《葬送的芙莉莲》让我想到时间、记忆和迟来的理解。"
        ));

        String prompt = engine.buildSystemPrompt(
                7L,
                "ws-1",
                "",
                List.of(),
                "",
                "聊聊芙莉莲关于时间的主题");

        assertThat(prompt)
                .contains("## 你知道的事")
                .contains("- 《葬送的芙莉莲》让我想到时间、记忆和迟来的理解。");
        verify(knowledgeService).searchRelevantKnowledge("聊聊芙莉莲关于时间的主题", 3);
    }

    @Test
    void injectsKnowledgeGraphAssociationsWhenQuestionMentionsKnownTopic() {
        KnowledgeGraphService graphService = mock(KnowledgeGraphService.class);
        ContextEngine engine = engineWith(new KnowledgeContextContributor(null, null, graphService));
        when(graphService.contextForQuestion("聊聊葬送的芙莉莲和时间", 5)).thenReturn(List.of(
                "Frieren -> time (RELATED_TO, weight=0.90): time and memory"
        ));

        String prompt = engine.buildSystemPrompt(
                7L,
                "ws-1",
                "",
                List.of(),
                "",
                "聊聊葬送的芙莉莲和时间");

        assertThat(prompt)
                .contains("## 话题关联")
                .contains("Frieren -> time (RELATED_TO, weight=0.90): time and memory");
        verify(graphService).contextForQuestion("聊聊葬送的芙莉莲和时间", 5);
    }

    @Test
    void injectsCurrentPostAnalysisWhenPageContextContainsPostId() {
        ContentIntelligenceReader contentService = mock(ContentIntelligenceReader.class);
        ContextEngine engine = engineWith(new PageContextContributor(contentService));
        when(contentService.getAnalysis(42L)).thenReturn(new ContentAnalysis(
                List.of(
                        new Entity("芙莉莲", "动漫作品名", 0.9),
                        new Entity("时间", "其他专有名词", 0.8)
                ),
                "原来是在说时间留下来的重量呢。",
                List.of(99L),
                Instant.parse("2026-07-04T08:00:00Z")));

        String prompt = engine.buildSystemPrompt(
                7L,
                "ws-1",
                "页面：/post/frieren-review\npostId：42\n标题：时间的重量",
                List.of(),
                "",
                "你怎么看这篇文章？");

        assertThat(prompt)
                .contains("## 当前文章")
                .contains("摘要：原来是在说时间留下来的重量呢。")
                .contains("涉及：芙莉莲、时间");
        verify(contentService).getAnalysis(42L);
    }

    @Test
    void injectsCurrentPostAnalysisWhenPageContextContainsPostSlug() {
        ContentIntelligenceReader contentService = mock(ContentIntelligenceReader.class);
        ContextEngine engine = engineWith(new PageContextContributor(contentService));
        when(contentService.getAnalysisBySlug("frieren-review")).thenReturn(new ContentAnalysis(
                List.of(new Entity("Frieren", "work", 0.9)),
                "post slug summary",
                List.of(),
                Instant.parse("2026-07-04T08:00:00Z")));

        String prompt = engine.buildSystemPrompt(
                7L,
                "ws-1",
                "page: /agent\nsource: post:frieren-review\npostSlug: frieren-review",
                List.of(),
                "",
                "What do you think about this post?");

        assertThat(prompt)
                .contains("post slug summary")
                .contains("Frieren");
        verify(contentService).getAnalysisBySlug("frieren-review");
    }

    private ContextEngine fullEngine(HybridSearchService hybridSearchService,
                                     KnowledgeService knowledgeService,
                                     ContentIntelligenceReader contentReader,
                                     SeasonService season,
                                     KnowledgeGraphService graphService) {
        return engineWith(
                new IdentityContextContributor(),
                new RelationshipContextContributor(stateService, season),
                new PageContextContributor(contentReader),
                new KnowledgeContextContributor(hybridSearchService, knowledgeService, graphService),
                new ProceduralContextContributor(),
                new ToolsContextContributor(),
                new HistoryContextContributor(),
                new QuestionContextContributor());
    }

    private ContextEngine engineWith(ContextContributor... contributors) {
        return new ContextEngine(anchorManager, List.of(contributors));
    }

    private AgentTool mockTool() {
        return new AgentTool() {
            @Override
            public String name() {
                return "test_tool";
            }

            @Override
            public String description() {
                return "测试工具";
            }

            @Override
            public Map<String, ParamDef> parameterSchema() {
                return Map.of("keyword", new ParamDef("关键词", String.class, true));
            }

            @Override
            public String execute(Map<String, Object> input, long userId) {
                return "mock observation";
            }
        };
    }

    private CharacterState defaultState() {
        return new CharacterState(
                new Personality(0.7, 0.8, 0.5),
                new Mood(-0.4, 0.5, 0.0),
                new Relationship(0.42, 8, Instant.parse("2026-07-03T00:00:00Z")),
                new Needs(0.0, 0.0, 0.0),
                new BehaviorProb(0.5, 0.3, 0.3)
        );
    }
}

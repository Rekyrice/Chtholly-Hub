package com.chtholly.agent.tools;

import com.chtholly.agent.config.AgentDomainConfig;
import com.chtholly.agent.config.BangumiDomainConfig;
import com.chtholly.agent.runtime.AgentToolExecutionException;
import com.chtholly.bangumi.service.BangumiService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BangumiToolFailureContractTest {

    private static final String USER_MESSAGE = "Bangumi 服务暂时不可用，请稍后再试。";

    private final BangumiService bangumiService = mock(BangumiService.class);
    private final AgentDomainConfig domainConfig = domainConfig();

    @Test
    void subjectSearchMapsProviderFailureToControlledUnavailableError() {
        when(bangumiService.search("迷宫饭", 5))
                .thenThrow(new IllegalStateException(USER_MESSAGE));
        BangumiSearchTool tool = new BangumiSearchTool(bangumiService, domainConfig);

        assertUnavailable(() -> tool.execute(Map.of("keyword", "迷宫饭"), 7L));
    }

    @Test
    void seriesSearchMapsProviderFailureToControlledUnavailableError() {
        when(bangumiService.searchAnimeSeries("迷宫饭", 15))
                .thenThrow(new IllegalStateException(USER_MESSAGE));
        BangumiSearchTool tool = new BangumiSearchTool(bangumiService, domainConfig);

        assertUnavailable(() -> tool.execute(Map.of(
                "keyword", "迷宫饭",
                "_userQuestion", "《迷宫饭》一共有几季？"), 7L));
    }

    @Test
    void characterSearchMapsProviderFailureToControlledUnavailableError() {
        when(bangumiService.describeSubjectCharacters("迷宫饭"))
                .thenThrow(new IllegalStateException(USER_MESSAGE));
        BangumiCharactersTool tool = new BangumiCharactersTool(
                bangumiService, domainConfig);

        assertUnavailable(() -> tool.execute(Map.of("keyword", "迷宫饭"), 7L));
    }

    @Test
    void personWorksMapsProviderFailureToControlledUnavailableError() {
        when(bangumiService.describePersonWorks("九井谅子", null, "all"))
                .thenThrow(new IllegalStateException(USER_MESSAGE));
        BangumiPersonWorksTool tool = new BangumiPersonWorksTool(bangumiService);

        assertUnavailable(() -> tool.execute(Map.of("keyword", "九井谅子"), 7L));
    }

    @Test
    void subjectSearchKeepsEmptyProviderResultAsNormalObservation() {
        when(bangumiService.search("不存在的条目", 5)).thenReturn(List.of());
        BangumiSearchTool tool = new BangumiSearchTool(bangumiService, domainConfig);

        assertThat(tool.execute(Map.of("keyword", "不存在的条目"), 7L))
                .isEqualTo("Bangumi 未找到与「不存在的条目」相关的条目。");
    }

    @Test
    void seriesSearchKeepsEmptyProviderResultAsNormalObservationWhenNormalizationPatternsAreAbsent() {
        when(bangumiService.searchAnimeSeries("不存在的系列", 15)).thenReturn(List.of());
        BangumiSearchTool tool = new BangumiSearchTool(bangumiService, domainConfig);

        assertThat(tool.execute(Map.of(
                "keyword", "不存在的系列",
                "_userQuestion", "《不存在的系列》一共有几季？"), 7L))
                .isEqualTo("Bangumi 未找到与「不存在的系列」相关的动画条目。");
    }

    @Test
    void characterSearchKeepsEmptyProviderResultAsNormalObservation() {
        when(bangumiService.describeSubjectCharacters("不存在的条目"))
                .thenReturn("Bangumi 未找到相关角色信息。");
        BangumiCharactersTool tool = new BangumiCharactersTool(
                bangumiService, domainConfig);

        assertThat(tool.execute(Map.of("keyword", "不存在的条目"), 7L))
                .isEqualTo("Bangumi 未找到与「不存在的条目」相关的角色信息。");
    }

    @Test
    void personWorksKeepsEmptyProviderResultAsNormalObservation() {
        when(bangumiService.describePersonWorks("不存在的人物", null, "all"))
                .thenReturn("Bangumi 未找到相关人物信息。");
        BangumiPersonWorksTool tool = new BangumiPersonWorksTool(bangumiService);

        assertThat(tool.execute(Map.of("keyword", "不存在的人物"), 7L))
                .isEqualTo("Bangumi 未找到相关人物或作品列表。");
    }

    private void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        AgentToolExecutionException.class,
                        failure -> {
                            assertThat(failure.errorCode()).isEqualTo("BANGUMI_UNAVAILABLE");
                            assertThat(failure.userMessage()).isEqualTo(USER_MESSAGE);
                            assertThat(failure.diagnosticAttributes())
                                    .containsEntry("provider", "bangumi");
                            assertThat(failure.getCause())
                                    .isInstanceOf(IllegalStateException.class);
                        });
    }

    private AgentDomainConfig domainConfig() {
        BangumiDomainConfig bangumi = mock(BangumiDomainConfig.class);
        when(bangumi.seasonQuestionRegex()).thenReturn("季");
        when(bangumi.noSubjectResult()).thenReturn("Bangumi 未找到与「{keyword}」相关的条目。");
        when(bangumi.noAnimeResult()).thenReturn("Bangumi 未找到与「{keyword}」相关的动画条目。");
        return new AgentDomainConfig(null, null, bangumi, null);
    }
}

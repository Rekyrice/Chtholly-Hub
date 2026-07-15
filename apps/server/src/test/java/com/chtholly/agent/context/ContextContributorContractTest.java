package com.chtholly.agent.context;

import com.chtholly.agent.anchor.AnchorContext;
import com.chtholly.agent.anchor.AnchorManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class ContextContributorContractTest {

    @Test
    void sortsContributorsByOrderBeforeRendering() {
        ContextEngine engine = engineWith(
                contributor("question", 800, request -> contribution("question", 800, "question")),
                contributor("identity", 100, request -> contribution("identity", 100, "identity")));

        assertThat(build(engine)).isEqualTo("identity\n\nquestion");
    }

    @Test
    void skipsEmptyContributions() {
        ContextEngine engine = engineWith(
                contributor("identity", 100, request -> contribution("identity", 100, "identity")),
                contributor("page", 300, request -> ContextContribution.empty("page", 300, false)),
                contributor("question", 800, request -> contribution("question", 800, "question")));

        assertThat(build(engine)).isEqualTo("identity\n\nquestion");
    }

    @Test
    void isolatesRuntimeExceptionAndContinuesWithLaterContributor() {
        ContextEngine engine = engineWith(
                contributor("identity", 100, request -> contribution("identity", 100, "identity")),
                contributor("knowledge", 400, request -> {
                    throw new IllegalStateException("knowledge unavailable");
                }),
                contributor("question", 800, request -> contribution("question", 800, "question")));

        assertThat(build(engine)).isEqualTo("identity\n\nquestion");
    }

    @Test
    void rejectsDuplicateContributorNames() {
        AnchorManager anchorManager = mock(AnchorManager.class);

        assertThatIllegalArgumentException().isThrownBy(() -> new ContextEngine(anchorManager, List.of(
                contributor("identity", 100, request -> contribution("identity", 100, "first")),
                contributor("identity", 200, request -> contribution("identity", 200, "second")))));
    }

    @Test
    void rejectsDuplicateContributorOrders() {
        AnchorManager anchorManager = mock(AnchorManager.class);

        assertThatIllegalArgumentException().isThrownBy(() -> new ContextEngine(anchorManager, List.of(
                contributor("identity", 100, request -> contribution("identity", 100, "first")),
                contributor("relationship", 100, request -> contribution("relationship", 100, "second")))));
    }

    @Test
    void doesNotIsolateAnchorManagerFailure() {
        AnchorManager anchorManager = mock(AnchorManager.class);
        when(anchorManager.buildContext(7L, "session-1"))
                .thenThrow(new IllegalStateException("anchor unavailable"));
        ContextEngine engine = new ContextEngine(anchorManager, List.of(
                contributor("question", 800, request -> contribution("question", 800, "question"))));

        assertThatThrownBy(() -> build(engine))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("anchor unavailable");
    }

    @Test
    void normalizesNullContributionToDegradedEmptyAndWarns(CapturedOutput output) {
        TestContributor contributor = contributor("identity", 100, request -> null);
        ContextEngine engine = engineWith(contributor);

        ContextContribution actual = engine.safeContribution(contributor, request());

        assertThat(actual).isEqualTo(ContextContribution.empty("identity", 100, true));
        assertThat(output).contains("identity", "null");
    }

    @Test
    void normalizesMismatchedMetadataAndWarns(CapturedOutput output) {
        TestContributor contributor = contributor("identity", 100,
                request -> new ContextContribution("wrong", 999, " content ", false));
        ContextEngine engine = engineWith(
                contributor,
                contributor("question", 800, request -> contribution("question", 800, "question")));

        ContextContribution actual = engine.safeContribution(contributor, request());

        assertThat(actual).isEqualTo(new ContextContribution("identity", 100, " content ", true));
        assertThat(build(engine)).isEqualTo("content\n\nquestion");
        assertThat(output).contains("identity", "metadata");
    }

    @Test
    void includesDegradedContentAndWarns(CapturedOutput output) {
        ContextEngine engine = engineWith(contributor("identity", 100,
                request -> new ContextContribution("identity", 100, "content", true)));

        assertThat(build(engine)).isEqualTo("content");
        assertThat(output).contains("identity", "degraded");
    }

    private ContextEngine engineWith(TestContributor... contributors) {
        AnchorManager anchorManager = mock(AnchorManager.class);
        when(anchorManager.buildContext(7L, "session-1")).thenReturn(AnchorContext.builder().build());
        return new ContextEngine(anchorManager, List.of(contributors));
    }

    private String build(ContextEngine engine) {
        return engine.buildSystemPrompt(7L, "session-1", "", List.of(), "", "question");
    }

    private ContextRequest request() {
        return new ContextRequest(
                7L, "session-1", "", List.of(), "", "question", AnchorContext.builder().build());
    }

    private static TestContributor contributor(
            String name,
            int order,
            Function<ContextRequest, ContextContribution> contribution) {
        return new TestContributor(name, order, contribution);
    }

    private static ContextContribution contribution(String name, int order, String content) {
        return new ContextContribution(name, order, content, false);
    }

    private record TestContributor(
            String name,
            int order,
            Function<ContextRequest, ContextContribution> contribution) implements ContextContributor {

        @Override
        public ContextContribution contribute(ContextRequest request) {
            return contribution.apply(request);
        }
    }
}

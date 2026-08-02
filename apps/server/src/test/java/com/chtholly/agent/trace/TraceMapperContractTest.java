package com.chtholly.agent.trace;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the mapper API and SQL contract for failure-pattern candidates. */
class TraceMapperContractTest {

    @Test
    void failureCandidateQueryIncludesTerminalAndPayloadFailures() throws Exception {
        assertThat(TraceMapper.class.getDeclaredMethods())
                .extracting(Method::getName)
                .contains("findUnanalyzedFailureCandidates");

        try (var input = getClass().getResourceAsStream("/mapper/TraceMapper.xml")) {
            assertThat(input).isNotNull();
            String xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            int start = xml.indexOf("<select id=\"findUnanalyzedFailureCandidates\"");
            int end = xml.indexOf("</select>", start);

            assertThat(start).isGreaterThanOrEqualTo(0);
            assertThat(end).isGreaterThan(start);
            assertThat(xml.substring(start, end))
                    .contains("pattern_analyzed = 0")
                    .contains("status != 'SUCCESS'")
                    .contains("JSON_EXTRACT(trace_payload, '$.failureType')")
                    .contains("'NONE'")
                    .doesNotContain("status = #{status}");
        }
    }
}

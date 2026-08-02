package com.chtholly.agent.trace;

import com.chtholly.agent.trace.dto.TraceDetailDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TraceControllerTest {

    @Test
    void listTracesConvertsDateInputsToAnInclusiveDayRange() {
        TraceQueryService service = mock(TraceQueryService.class);
        TraceController controller = new TraceController(service);
        ZoneId zone = ZoneId.systemDefault();

        controller.listTraces(
                1,
                20,
                TraceStatus.TIMEOUT.name(),
                7L,
                "2026-07-03",
                "2026-07-05",
                "corr-7");

        verify(service).listTraces(
                1,
                20,
                TraceStatus.TIMEOUT.name(),
                7L,
                LocalDate.parse("2026-07-03").atStartOfDay(zone).toInstant(),
                LocalDate.parse("2026-07-06").atStartOfDay(zone).toInstant(),
                "corr-7");
    }

    @Test
    void getTraceExposesOnlyTheSafeProjectionContract() {
        TraceQueryService service = mock(TraceQueryService.class);
        TraceController controller = new TraceController(service);
        TraceDetailDto detail = new TraceDetailDto(
                "corr-safe",
                7L,
                "session-safe",
                TraceStatus.SUCCESS.name(),
                25,
                0,
                null,
                TraceDetailDto.Compatibility.UNSUPPORTED,
                TraceDetailDto.TimingAccuracy.NONE,
                List.of(),
                null);
        when(service.getTrace("corr-safe")).thenReturn(detail);

        JsonNode json = new ObjectMapper().valueToTree(controller.getTrace("corr-safe"));

        assertThat(json.path("compatibility").asText()).isEqualTo("UNSUPPORTED");
        assertThat(json.path("timingAccuracy").asText()).isEqualTo("NONE");
        assertThat(json.path("phases").isArray()).isTrue();
        assertThat(json.has("tracePayload")).isFalse();
        assertThat(json.has("toolCalls")).isFalse();
        assertThat(json.has("steps")).isFalse();
        assertThat(json.has("unassigned")).isFalse();
        verify(service).getTrace("corr-safe");
    }
}

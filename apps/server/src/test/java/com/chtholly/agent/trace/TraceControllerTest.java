package com.chtholly.agent.trace;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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
}

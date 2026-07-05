package com.chtholly.agent.trace;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface FailurePatternMapper {

    TraceFailurePatternRow findByPatternKey(@Param("patternKey") String patternKey);

    int insert(TraceFailurePatternRow row);

    int updatePattern(@Param("patternKey") String patternKey,
                      @Param("increment") int increment,
                      @Param("lastSeenAt") Instant lastSeenAt,
                      @Param("sampleTraceIds") String sampleTraceIds);

    List<TraceFailurePatternRow> listAllOrderByCountDesc(@Param("limit") int limit);

    List<TraceFailurePatternRow> listBetweenOrderByCountDesc(@Param("from") Instant from,
                                                             @Param("to") Instant to,
                                                             @Param("limit") int limit);
}

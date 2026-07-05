package com.chtholly.agent.trace;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface TraceMapper {

    int insert(ExecutionTraceRow row);

    ExecutionTraceRow findByCorrelationId(@Param("correlationId") String correlationId);

    List<ExecutionTraceRow> list(@Param("status") String status,
                                 @Param("userId") Long userId,
                                 @Param("limit") int limit,
                                 @Param("offset") int offset);

    long count(@Param("status") String status, @Param("userId") Long userId);

    List<ExecutionTraceRow> findUnanalyzedByStatus(@Param("status") String status, @Param("limit") int limit);

    int markPatternAnalyzed(@Param("ids") List<Long> ids);

    long countSince(@Param("since") Instant since);

    long countByStatusSince(@Param("status") String status, @Param("since") Instant since);

    Double avgDurationSince(@Param("since") Instant since);

    List<Integer> listDurationsSince(@Param("since") Instant since, @Param("limit") int limit);

    List<TraceTokenTrendRow> tokenTrendSince(@Param("since") Instant since);

    long countBetween(@Param("from") Instant from, @Param("to") Instant to);

    long countByStatusBetween(@Param("status") String status,
                              @Param("from") Instant from,
                              @Param("to") Instant to);

    Double avgDurationBetween(@Param("from") Instant from, @Param("to") Instant to);

    List<Integer> listDurationsBetween(@Param("from") Instant from,
                                       @Param("to") Instant to,
                                       @Param("limit") int limit);

    List<TraceTokenTrendRow> tokenTrendBetween(@Param("from") Instant from, @Param("to") Instant to);

    List<TraceDailyTrendRow> executionTrendBetween(@Param("from") Instant from, @Param("to") Instant to);
}

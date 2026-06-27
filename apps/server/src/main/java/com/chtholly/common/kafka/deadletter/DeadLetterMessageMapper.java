package com.chtholly.common.kafka.deadletter;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DeadLetterMessageMapper {

    int insert(@Param("id") long id,
               @Param("sourceTopic") String sourceTopic,
               @Param("messageKey") String messageKey,
               @Param("messageValue") String messageValue,
               @Param("exceptionClass") String exceptionClass,
               @Param("exceptionMessage") String exceptionMessage,
               @Param("retryCount") int retryCount,
               @Param("status") String status);

    DeadLetterMessageRow findById(@Param("id") long id);

    List<DeadLetterMessageRow> list(@Param("topic") String topic,
                                    @Param("status") String status,
                                    @Param("limit") int limit,
                                    @Param("offset") int offset);

    long count(@Param("topic") String topic, @Param("status") String status);

    int updateStatus(@Param("id") long id, @Param("status") String status);
}

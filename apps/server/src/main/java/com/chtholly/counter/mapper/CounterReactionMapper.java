package com.chtholly.counter.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** Persists and queries authoritative like/favorite membership facts. */
@Mapper
public interface CounterReactionMapper {

    /** Inserts one fact only when it does not already exist. */
    int insertIgnore(
            @Param("entityType") String entityType,
            @Param("entityId") String entityId,
            @Param("metric") String metric,
            @Param("userId") long userId);

    /** Deletes one fact only when it currently exists. */
    int delete(
            @Param("entityType") String entityType,
            @Param("entityId") String entityId,
            @Param("metric") String metric,
            @Param("userId") long userId);

    /** Returns whether one authoritative fact currently exists. */
    int exists(
            @Param("entityType") String entityType,
            @Param("entityId") String entityId,
            @Param("metric") String metric,
            @Param("userId") long userId);

    /** Returns the subset of relation keys that currently exist. */
    List<CounterReactionKey> findExisting(@Param("keys") List<CounterReactionKey> keys);

    /** Returns existing entity IDs for one user and metric. */
    List<String> findExistingEntityIds(
            @Param("entityType") String entityType,
            @Param("metric") String metric,
            @Param("userId") long userId,
            @Param("entityIds") List<String> entityIds);

    /** Pages authoritative user IDs for one entity and metric by primary-key order. */
    List<Long> listUserIdsAfter(
            @Param("entityType") String entityType,
            @Param("entityId") String entityId,
            @Param("metric") String metric,
            @Param("afterUserId") long afterUserId,
            @Param("limit") int limit);

    /** Inserts a bounded batch of facts, ignoring facts that already exist. */
    int insertAllIgnore(@Param("keys") List<CounterReactionKey> keys);

    /** Deletes a bounded explicit batch of facts. */
    int deleteAll(@Param("keys") List<CounterReactionKey> keys);
}

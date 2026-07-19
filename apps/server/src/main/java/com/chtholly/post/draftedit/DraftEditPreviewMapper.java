package com.chtholly.post.draftedit;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;

/** Persists and atomically transitions controlled draft-edit preview records. */
@Mapper
public interface DraftEditPreviewMapper {

    void insert(DraftEditPreview preview);

    DraftEditPreview findById(@Param("id") long id);

    DraftEditPreview findByIdForUpdate(@Param("id") long id);

    int markApplied(@Param("id") long id, @Param("decidedAt") Instant decidedAt);

    int markRejected(@Param("id") long id, @Param("decidedAt") Instant decidedAt);

    int markExpired(@Param("id") long id, @Param("decidedAt") Instant decidedAt);
}

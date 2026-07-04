package com.chtholly.agent.experience;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * MyBatis mapper for long-term archived agent experiences.
 */
@Mapper
public interface ArchivedExperienceMapper {

    /**
     * Archives one memorable experience.
     *
     * @param experience memorable experience
     */
    @Insert("""
            INSERT INTO archived_experiences (text, importance, source, created_at, archived_at)
            VALUES (#{experience.text}, #{experience.importance}, #{experience.source}, #{experience.createdAt}, NOW())
            """)
    void archive(@Param("experience") Experience experience);

    /**
     * Lists memorable archived experiences newest first.
     *
     * @param limit max rows
     * @return archived experiences
     */
    @Select("""
            SELECT id, text, importance, source, created_at AS createdAt, archived_at AS archivedAt
            FROM archived_experiences
            ORDER BY created_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<ArchivedExperience> listRecentMemorable(@Param("limit") int limit);
}

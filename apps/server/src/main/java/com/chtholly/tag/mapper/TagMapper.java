package com.chtholly.tag.mapper;

import com.chtholly.tag.model.Tag;
import com.chtholly.tag.model.TagListEtagRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TagMapper {

    List<Tag> listOrderByUsage(@Param("limit") int limit);

    TagListEtagRow etagFingerprint(@Param("limit") int limit);

    Tag findByName(@Param("name") String name);

    Tag findBySlug(@Param("slug") String slug);

    void upsert(@Param("name") String name, @Param("slug") String slug, @Param("creatorId") Long creatorId);

    int incrementUsage(@Param("name") String name);

    int decrementUsage(@Param("name") String name);
}

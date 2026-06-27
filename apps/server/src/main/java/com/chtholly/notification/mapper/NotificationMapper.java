package com.chtholly.notification.mapper;

import com.chtholly.notification.model.NotificationCountStats;
import com.chtholly.notification.model.NotificationRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface NotificationMapper {

    void insert(@Param("id") long id,
                @Param("userId") long userId,
                @Param("type") String type,
                @Param("payload") String payload);

    List<NotificationRow> listByUser(@Param("userId") long userId,
                                       @Param("limit") int limit,
                                       @Param("offset") int offset);

    NotificationCountStats countStatsByUser(@Param("userId") long userId);

    long countUnread(@Param("userId") long userId);

    long countUnreadLikePost(@Param("userId") long userId,
                             @Param("type") String type,
                             @Param("postId") long postId);

    NotificationRow findById(@Param("id") long id);

    int markRead(@Param("id") long id, @Param("userId") long userId);

    int markAllRead(@Param("userId") long userId);

    int deleteExpiredRead(@Param("days") int days);

    int deleteExpiredReadByUser(@Param("userId") long userId, @Param("days") int days);
}

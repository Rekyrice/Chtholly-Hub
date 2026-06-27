package com.chtholly.user.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import com.chtholly.user.domain.User;
import java.util.List;

@Mapper
public interface UserMapper {

    User findByPhone(@Param("phone") String phone);

    User findByEmail(@Param("email") String email);

    boolean existsByPhone(@Param("phone") String phone);

    boolean existsByEmail(@Param("email") String email);

    void insert(User user);

    User findById(@Param("id") Long id);

    User findByHandle(@Param("handle") String handle);

    void updatePassword(@Param("id") Long id, @Param("passwordHash") String passwordHash);

    void updateProfile(User user);

    boolean existsByHandleExceptId(@Param("handle") String handle, @Param("excludeId") Long excludeId);

    List<User> listByIds(@Param("ids") List<Long> ids);

    int updateRole(@Param("id") Long id, @Param("role") String role);

    int updateBannedAt(@Param("id") Long id, @Param("bannedAt") java.time.Instant bannedAt);

    List<User> searchUsers(@Param("keyword") String keyword, @Param("limit") int limit, @Param("offset") int offset);

    long countSearchUsers(@Param("keyword") String keyword);
}

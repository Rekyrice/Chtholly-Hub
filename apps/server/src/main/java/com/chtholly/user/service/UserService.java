package com.chtholly.user.service;

import com.chtholly.user.domain.User;
import java.util.Optional;

/**
 * 用户服务接口。
 */
public interface UserService {

    Optional<User> findByPhone(String phone);

    Optional<User> findByEmail(String email);

    Optional<User> findByHandle(String handle);

    Optional<User> findById(long id);

    boolean existsByPhone(String phone);

    boolean existsByEmail(String email);

    boolean existsByHandle(String handle);

    User createUser(User user);

    void updatePassword(User user);
}
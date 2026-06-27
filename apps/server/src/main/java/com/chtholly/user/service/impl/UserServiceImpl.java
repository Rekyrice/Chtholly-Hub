package com.chtholly.user.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

import com.chtholly.user.service.UserService;
import com.chtholly.user.mapper.UserMapper;
import com.chtholly.user.domain.User;

/**
 * Internal user persistence service for authentication and account management.
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;

    /**
     * Finds a user by phone number.
     *
     * @param phone phone number
     * @return matching user, or empty if not found
     */
    @Transactional(readOnly = true)
    public Optional<User> findByPhone(String phone) {
        return Optional.ofNullable(userMapper.findByPhone(phone));
    }

    /**
     * Finds a user by email address.
     *
     * @param email email address
     * @return matching user, or empty if not found
     */
    @Transactional(readOnly = true)
    public Optional<User> findByEmail(String email) {
        return Optional.ofNullable(userMapper.findByEmail(email));
    }

    /**
     * Finds a user by primary key.
     *
     * @param id user ID
     * @return matching user, or empty if not found
     */
    @Transactional(readOnly = true)
    public Optional<User> findById(long id) {
        return Optional.ofNullable(userMapper.findById(id));
    }

    /**
     * Checks whether a phone number is already registered.
     *
     * @param phone phone number
     * @return {@code true} if a user with the phone exists
     */
    @Transactional(readOnly = true)
    public boolean existsByPhone(String phone) {
        return userMapper.existsByPhone(phone);
    }

    /**
     * Checks whether an email address is already registered.
     *
     * @param email email address
     * @return {@code true} if a user with the email exists
     */
    @Transactional(readOnly = true)
    public boolean existsByEmail(String email) {
        return userMapper.existsByEmail(email);
    }

    /**
     * Creates a new user with creation and update timestamps.
     *
     * @param user user entity to persist
     * @return the persisted user entity
     */
    @Transactional
    public User createUser(User user) {
        Instant now = Instant.now();
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userMapper.insert(user);
        return user;
    }

    /**
     * Updates a user's password hash and refresh timestamp.
     *
     * @param user user entity containing ID and new password hash
     */
    @Transactional
    public void updatePassword(User user) {
        user.setUpdatedAt(Instant.now());
        userMapper.updatePassword(user.getId(), user.getPasswordHash());
    }
}

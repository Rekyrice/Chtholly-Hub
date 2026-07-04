package com.chtholly.auth.event;

import com.chtholly.user.domain.User;

/**
 * Published after a user is successfully registered.
 *
 * @param user newly registered user
 */
public record UserRegisteredEvent(User user) {
}

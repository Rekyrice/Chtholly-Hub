package com.chtholly.auth.service;

import com.chtholly.auth.api.dto.AuthUserResponse;
import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import com.chtholly.user.domain.User;
import org.springframework.stereotype.Service;

/** Loads the authenticated user's stable account summary. */
@Service
public class AuthCurrentUserService {

    private final AuthIdentityPolicy identityPolicy;

    /** Creates the current-user query use case. */
    public AuthCurrentUserService(AuthIdentityPolicy identityPolicy) {
        this.identityPolicy = identityPolicy;
    }

    /** Returns the current user or the existing identifier-not-found error. */
    public AuthUserResponse me(long userId) {
        User user = identityPolicy.findUserById(userId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.IDENTIFIER_NOT_FOUND));
        return AuthResponseMapper.toUser(user);
    }
}

package com.chtholly.user.service.impl;

import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.user.api.dto.PublicUserResponse;
import com.chtholly.user.domain.User;
import com.chtholly.user.mapper.UserMapper;
import com.chtholly.user.service.UserPublicService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserPublicServiceImpl implements UserPublicService {

    private final UserMapper userMapper;
    private final PostMapper postMapper;

    @Override
    public PublicUserResponse getByHandle(String handle) {
        if (handle == null || handle.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户标识不能为空");
        }
        User user = userMapper.findByHandle(handle.trim());
        if (user == null) {
            throw new BusinessException(ErrorCode.IDENTIFIER_NOT_FOUND, "用户不存在");
        }
        long postCount = postMapper.countPublicPublishedByCreator(user.getId());
        return new PublicUserResponse(
                String.valueOf(user.getId()),
                user.getHandle(),
                user.getNickname(),
                user.getAvatar(),
                user.getBio(),
                postCount
        );
    }
}

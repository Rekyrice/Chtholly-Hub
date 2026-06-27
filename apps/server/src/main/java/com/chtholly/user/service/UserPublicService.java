package com.chtholly.user.service;

import com.chtholly.user.api.dto.PublicUserResponse;

public interface UserPublicService {

    /** 按 handle 查询公开资料；不存在时抛出业务异常。 */
    PublicUserResponse getByHandle(String handle);

    /** 公开资料 ETag：hash(updatedAt)。 */
    String computeEtagByHandle(String handle);
}

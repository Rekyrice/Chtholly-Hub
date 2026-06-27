package com.chtholly.user.api;

import com.chtholly.user.api.dto.PublicUserResponse;
import com.chtholly.user.service.UserPublicService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 用户公开 API：个人主页资料。 */
@RestController
@RequestMapping("/api/v1/users")
@Validated
@RequiredArgsConstructor
public class UserController {

    private final UserPublicService userPublicService;

    @GetMapping("/{handle}")
    public PublicUserResponse profile(@PathVariable("handle") String handle) {
        return userPublicService.getByHandle(handle);
    }
}

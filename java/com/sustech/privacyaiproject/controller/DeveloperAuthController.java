package com.sustech.privacyaiproject.controller;

import com.sustech.privacyaiproject.common.result.Result;
import com.sustech.privacyaiproject.domain.entity.DeveloperAccountEntity;
import com.sustech.privacyaiproject.service.DeveloperAuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 开发者控制台认证接口。
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class DeveloperAuthController {

    private final DeveloperAuthService developerAuthService;

    @PostMapping("/register")
    public Result<Map<String, Object>> register(@RequestBody AuthRequest request) {
        return Result.success(developerAuthService.register(request.username(), request.password()));
    }

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody AuthRequest request) {
        return Result.success(developerAuthService.login(request.username(), request.password()));
    }

    @GetMapping("/me")
    public Result<Map<String, Object>> me(@RequestHeader("Authorization") String authorization) {
        DeveloperAccountEntity account = developerAuthService.requireDeveloper(authorization);
        return Result.success(developerAuthService.userView(account));
    }

    @PostMapping("/change-password")
    public Result<String> changePassword(@RequestHeader("Authorization") String authorization,
                                         @RequestBody ChangePasswordRequest request) {
        DeveloperAccountEntity account = developerAuthService.requireDeveloper(authorization);
        developerAuthService.changePassword(account, request.oldPassword(), request.newPassword());
        return Result.success("密码修改成功");
    }

    public record AuthRequest(String username, String password) {
    }

    public record ChangePasswordRequest(String oldPassword, String newPassword) {
    }
}

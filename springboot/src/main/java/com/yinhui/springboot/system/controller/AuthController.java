package com.yinhui.springboot.system.controller;

import com.yinhui.springboot.common.result.Result;
import com.yinhui.springboot.common.security.CurrentUser;
import com.yinhui.springboot.system.dto.LoginRequest;
import com.yinhui.springboot.system.dto.LoginResponse;
import com.yinhui.springboot.system.dto.UserInfo;
import com.yinhui.springboot.system.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.success(authService.login(request));
    }

    @GetMapping("/me")
    public Result<UserInfo> me(@AuthenticationPrincipal CurrentUser principal) {
        return Result.success(principal.user());
    }

    @PostMapping("/logout")
    public Result<Void> logout(@AuthenticationPrincipal CurrentUser principal) {
        authService.logout(principal);
        return Result.success(null);
    }
}

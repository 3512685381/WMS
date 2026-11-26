package com.yinhui.springboot.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yinhui.springboot.common.exception.ApiException;
import com.yinhui.springboot.common.security.CurrentUser;
import com.yinhui.springboot.common.security.JwtUtil;
import com.yinhui.springboot.system.dto.LoginRequest;
import com.yinhui.springboot.system.dto.LoginResponse;
import com.yinhui.springboot.system.dto.UserInfo;
import com.yinhui.springboot.system.entity.SysUser;
import com.yinhui.springboot.system.mapper.SysUserMapper;
import com.yinhui.springboot.system.mapper.SysUserRoleMapper;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwt;
    private final TokenBlacklistService blacklist;
    private final String dummyPasswordHash;

    public AuthService(SysUserMapper userMapper, SysUserRoleMapper userRoleMapper,
            PasswordEncoder passwordEncoder, JwtUtil jwt, TokenBlacklistService blacklist) {
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwt = jwt;
        this.blacklist = blacklist;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "密码的 UTF-8 编码最多可占用 72 字节");
        }
        var query = Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, request.username().strip());
        SysUser user = userMapper.selectOne(query);
        boolean enabled = isEnabled(user);
        String hash = enabled ? user.getPassword() : dummyPasswordHash;
        boolean matches = passwordEncoder.matches(request.password(), hash);
        if (!enabled || !matches) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }

        UserInfo info = loadUserInfo(user);
        var issued = jwt.issue(info.id(), info.username(), info.roles());
        return new LoginResponse(issued.value(), "Bearer", issued.expiresIn(), info);
    }

    @Transactional(readOnly = true)
    public CurrentUser authenticate(String token) {
        var verified = jwt.verify(token);
        if (blacklist.isRevoked(verified.jti())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "登录凭证已注销");
        }
        var query = Wrappers.<SysUser>lambdaQuery()
                .select(SysUser::getId, SysUser::getUsername, SysUser::getNickname, SysUser::getStatus)
                .eq(SysUser::getId, verified.userId());
        SysUser user = userMapper.selectOne(query);
        if (!isEnabled(user)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "登录凭证已失效");
        }
        return new CurrentUser(loadUserInfo(user), verified.jti(), verified.expiresAt());
    }

    public void logout(CurrentUser principal) {
        blacklist.revoke(principal.jti(), principal.expiresAt());
    }

    private UserInfo loadUserInfo(SysUser user) {
        var grants = userRoleMapper.selectActiveGrants(user.getId());
        var roles = grants.stream()
                .map(SysUserRoleMapper.RoleGrant::roleCode)
                .distinct()
                .sorted(Comparator.comparingInt(AuthService::roleOrder).thenComparing(Comparator.naturalOrder()))
                .toList();
        if (roles.isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "当前账号暂无可用角色");
        }
        var permissions = grants.stream()
                .map(SysUserRoleMapper.RoleGrant::permission)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        return new UserInfo(user.getId(), user.getUsername(), user.getNickname(), roles.getFirst(), roles, permissions);
    }

    private static boolean isEnabled(SysUser user) {
        return user != null && Integer.valueOf(1).equals(user.getStatus());
    }

    private static int roleOrder(String role) {
        return switch (role) {
            case "ADMIN" -> 0;
            case "OPERATOR" -> 1;
            case "USER" -> 2;
            default -> 3;
        };
    }
}

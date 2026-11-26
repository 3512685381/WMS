package com.yinhui.springboot.common.security;

import com.yinhui.springboot.system.dto.UserInfo;
import java.time.Instant;

public record CurrentUser(UserInfo user, String jti, Instant expiresAt) {
}

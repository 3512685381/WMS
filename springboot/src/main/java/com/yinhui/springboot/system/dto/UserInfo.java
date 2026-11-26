package com.yinhui.springboot.system.dto;

import java.util.List;

public record UserInfo(long id, String username, String nickname, String roleCode, List<String> roles,
                       List<String> permissions) {
}

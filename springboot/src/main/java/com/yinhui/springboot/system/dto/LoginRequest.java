package com.yinhui.springboot.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank(message = "请输入用户名")
        @Size(max = 64, message = "用户名最多 64 个字符")
        String username,

        @NotBlank(message = "请输入密码")
        @Size(max = 72, message = "密码过长")
        String password) {

    @Override
    public String toString() {
        return "LoginRequest[username=" + username + "]";
    }
}

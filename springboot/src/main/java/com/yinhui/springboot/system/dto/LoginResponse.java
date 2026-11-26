package com.yinhui.springboot.system.dto;

public record LoginResponse(String token, String tokenType, long expiresIn, UserInfo user) {

    @Override
    public String toString() {
        return "LoginResponse[tokenType=" + tokenType + ", expiresIn=" + expiresIn + "]";
    }
}

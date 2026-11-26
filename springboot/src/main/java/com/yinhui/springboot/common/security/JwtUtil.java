package com.yinhui.springboot.common.security;

import com.yinhui.springboot.common.exception.ApiException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class JwtUtil {

    private final SecretKey signingKey;
    private final JwtParser parser;
    private final long expireSeconds;

    public JwtUtil(JwtProperties properties) {
        byte[] keyBytes;
        try {
            keyBytes = Base64.getUrlDecoder().decode(properties.secret());
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException("JWT_SECRET 必须是 Base64URL 编码的密钥");
        }
        if (keyBytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET 解码后须至少有 32 字节");
        }

        this.signingKey = new SecretKeySpec(keyBytes, "HmacSHA256");
        this.parser = Jwts.parser()
                .verifyWith(signingKey)
                .sig().clear().add(Jwts.SIG.HS256).and()
                .build();
        this.expireSeconds = properties.expireSeconds();
    }

    public IssuedToken issue(long userId, String username, List<String> roles) {
        Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String token = Jwts.builder()
                .subject(Long.toString(userId))
                .id(UUID.randomUUID().toString())
                .claim("username", username)
                .claim("roleCode", roles.getFirst())
                .claim("roles", roles)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(issuedAt.plusSeconds(expireSeconds)))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
        return new IssuedToken(token, expireSeconds);
    }

    public VerifiedToken verify(String token) {
        try {
            var claims = parser.parseSignedClaims(token).getPayload();
            long userId = Long.parseLong(claims.getSubject());
            String jti = claims.getId();
            Date expiration = claims.getExpiration();
            if (userId <= 0 || jti == null || !UUID.fromString(jti).toString().equals(jti)
                    || expiration == null || !expiration.toInstant().isAfter(Instant.now())) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "登录凭证已失效");
            }
            return new VerifiedToken(userId, jti, expiration.toInstant());
        } catch (JwtException | IllegalArgumentException error) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "登录凭证无效或已过期");
        }
    }

    public record IssuedToken(String value, long expiresIn) {
        @Override
        public String toString() {
            return "IssuedToken[expiresIn=" + expiresIn + "]";
        }
    }

    public record VerifiedToken(long userId, String jti, Instant expiresAt) {
    }
}
